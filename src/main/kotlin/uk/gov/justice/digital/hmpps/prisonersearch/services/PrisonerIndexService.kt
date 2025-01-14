package uk.gov.justice.digital.hmpps.prisonersearch.services

import com.amazonaws.util.IOUtils
import com.google.gson.JsonParser
import com.microsoft.applicationinsights.TelemetryClient
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.client.Request
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.elasticsearch.UncategorizedElasticsearchException
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates
import org.springframework.http.HttpStatus.CONFLICT
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.prisonersearch.config.IndexProperties
import uk.gov.justice.digital.hmpps.prisonersearch.model.IndexStatus
import uk.gov.justice.digital.hmpps.prisonersearch.model.Prisoner
import uk.gov.justice.digital.hmpps.prisonersearch.model.PrisonerA
import uk.gov.justice.digital.hmpps.prisonersearch.model.PrisonerB
import uk.gov.justice.digital.hmpps.prisonersearch.model.SyncIndex
import uk.gov.justice.digital.hmpps.prisonersearch.model.translate
import uk.gov.justice.digital.hmpps.prisonersearch.repository.PrisonerARepository
import uk.gov.justice.digital.hmpps.prisonersearch.repository.PrisonerBRepository
import uk.gov.justice.digital.hmpps.prisonersearch.services.dto.OffenderBooking
import uk.gov.justice.digital.hmpps.prisonersearch.services.exceptions.ElasticSearchIndexingException
import kotlin.jvm.Throws

@Service
class PrisonerIndexService(
  private val nomisService: NomisService,
  private val prisonerARepository: PrisonerARepository,
  private val prisonerBRepository: PrisonerBRepository,
  private val indexQueueService: IndexQueueService,
  private val indexStatusService: IndexStatusService,
  private val searchClient: SearchClient,
  private val telemetryClient: TelemetryClient,
  private val indexProperties: IndexProperties,
) {
  companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun indexPrisoner(prisonerId: String): Prisoner? =
    nomisService.getOffender(prisonerId)?.let {
      sync(it)
    } ?: run {
      telemetryClient.trackEvent(
        "POSOffenderNotFoundForIndexing",
        mapOf("prisonerID" to prisonerId),
        null
      )
      return null
    }

  fun delete(prisonerNumber: String) {
    log.info("Delete Prisoner {}", prisonerNumber)

    prisonerARepository.deleteById(prisonerNumber)
    prisonerBRepository.deleteById(prisonerNumber)
  }

  fun sync(offenderBooking: OffenderBooking): Prisoner {

    val prisonerA = translate(PrisonerA(), offenderBooking)
    val prisonerB = translate(PrisonerB(), offenderBooking)
    val storedPrisoner: Prisoner

    val currentIndexStatus = indexStatusService.getCurrentIndex()
    if (currentIndexStatus.currentIndex == SyncIndex.INDEX_A) {
      storedPrisoner = prisonerARepository.save(prisonerA)
    } else {
      storedPrisoner = prisonerBRepository.save(prisonerB)
    }

    if (currentIndexStatus.inProgress) { // Keep changes in sync if rebuilding
      if (currentIndexStatus.currentIndex == SyncIndex.INDEX_A) {
        prisonerBRepository.save(prisonerB)
      } else {
        prisonerARepository.save(prisonerA)
      }
    }

    return storedPrisoner
  }

  private fun checkIfIndexExists(indexName: String): Boolean {
    val response = searchClient.lowLevelClient().performRequest(Request("HEAD", "/$indexName"))
    return response.statusLine.statusCode != 404
  }

  fun countIndex(prisonerIndex: SyncIndex): Int {
    val response = searchClient.lowLevelClient().performRequest(Request("get", "/${prisonerIndex.indexName}/_count"))
    return JsonParser.parseString(IOUtils.toString(response.entity.content)).asJsonObject["count"].asInt
  }

  @Throws(ElasticSearchIndexingException::class, ResponseStatusException::class)
  fun buildIndex(): IndexStatus {
    try {
      if (indexStatusService.markRebuildStarting()) {
        val currentIndex = indexStatusService.getCurrentIndex().currentIndex
        val otherIndexCount = countIndex(currentIndex.otherIndex())
        log.info(
          "Current index is {} [{}], rebuilding index {} [{}]",
          currentIndex,
          countIndex(currentIndex),
          currentIndex.otherIndex(),
          otherIndexCount
        )
        checkExistsAndReset(currentIndex.otherIndex())

        log.info("Sending rebuild request")
        indexQueueService.sendIndexRequestMessage(PrisonerIndexRequest(IndexRequestType.REBUILD))
      } else {
        log.info("Index not restarted as index is marked in progress or in error")
        throw ResponseStatusException(CONFLICT, "Unable to build index - it is marked as in progress or in error")
      }
      return indexStatusService.getCurrentIndex()
    } catch (ese: ElasticsearchException) {
      indexStatusService.markIndexBuildFailure()
      throw ElasticSearchIndexingException(ese)
    } catch (uee: UncategorizedElasticsearchException) {
      indexStatusService.markIndexBuildFailure()
      throw ElasticSearchIndexingException(uee)
    }
  }

  internal fun checkExistsAndReset(prisonerIndex: SyncIndex) {
    if (checkIfIndexExists(prisonerIndex.indexName)) {
      searchClient.lowLevelClient().performRequest(Request("DELETE", "/${prisonerIndex.indexName}"))
    }
    await untilCallTo { checkIfIndexExists(prisonerIndex.indexName) } matches { it == false }
    createPrisonerIndex(prisonerIndex)
  }

  private fun createPrisonerIndex(prisonerIndex: SyncIndex) {
    val indexOperations = searchClient.elasticsearchOperations().indexOps(IndexCoordinates.of(prisonerIndex.indexName))
    indexOperations.create()
    indexOperations.putMapping(indexOperations.createMapping(if (prisonerIndex == SyncIndex.INDEX_A) PrisonerA::class.java else PrisonerB::class.java))
  }

  fun cancelIndex(): IndexStatus {
    indexStatusService.cancelIndexing()
    return indexStatusService.getCurrentIndex()
  }

  @Throws(ResponseStatusException::class)
  fun switchIndex(): IndexStatus {
    val switched = indexStatusService.switchIndex()
    if (!switched) {
      log.info("Index not switched as one is marked in progress or in error")
      throw ResponseStatusException(CONFLICT, "Unable to switch indexes - one is marked as in progress or in error")
    }
    val currentIndex = indexStatusService.getCurrentIndex()
    log.info("Index switched, index {} is now current.", currentIndex.currentIndex)
    return currentIndex
  }

  fun addOffendersToBeIndexed(pageRequest: PageRequest) {
    var count = 0
    log.debug("Sending offender indexing requests row {} --> {}", pageRequest.offset + 1, pageRequest.offset + pageRequest.pageSize)
    nomisService.getOffendersIds(pageRequest.offset, pageRequest.pageSize).offenderIds?.forEach {
      indexQueueService.sendIndexRequestMessage(PrisonerIndexRequest(IndexRequestType.OFFENDER, it.offenderNumber))
      count += 1
    }
    log.debug("Requested {} offender index syncs", count)
  }

  @Throws(ResponseStatusException::class)
  fun indexingComplete(ignoreThreshold: Boolean): IndexStatus {
    val currentIndexStatus = indexStatusService.getCurrentIndex()
    if (currentIndexStatus.inError) {
      log.info("Index not marked as complete as it is in error")
      throw ResponseStatusException(CONFLICT, "Unable to marked index complete as it is in error")
    }
    if (ignoreThreshold.not()) {
      val indexCount = countIndex(currentIndexStatus.currentIndex.otherIndex())
      if (indexCount <= indexProperties.completeThreshold) {
        log.info("Ignoring index build request, index ${currentIndexStatus.currentIndex.otherIndex()} has count $indexCount which is less than threshold ${indexProperties.completeThreshold}.")
        return currentIndexStatus
      }
    }

    if (indexStatusService.markRebuildComplete()) {
      indexQueueService.clearAllMessages()
    }
    return indexStatusService.getCurrentIndex()
  }

  fun addIndexRequestToQueue(): Long {
    log.debug("Sending list of offender requests")
    var page = 0
    val totalRows = nomisService.getOffendersIds(0, 1).totalRows
    if (totalRows > 0) {
      do {
        indexQueueService.sendIndexRequestMessage(
          PrisonerIndexRequest(
            IndexRequestType.OFFENDER_LIST,
            null,
            PageRequest.of(page, indexProperties.pageSize)
          )
        )
        page += 1
      } while ((page) * indexProperties.pageSize < totalRows && indexStatusService.getCurrentIndex().inProgress && indexStatusService.getCurrentIndex().inError.not())
    }
    log.debug("Offender lists have been sent: {} requests for a total of {} offenders", page, totalRows)
    return totalRows
  }
}
