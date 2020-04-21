package uk.org.justice.digital.hmpps.prisonersearch.services

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.LocalDate

@Service
class NomisService(val prisonWebClient: WebClient,
                      @Value("\${api.offender.timeout:5s}") val offenderTimeout: Duration) {

    private val prisoners = object : ParameterizedTypeReference<List<OffenderBooking>>() {}
    private val ids = object : ParameterizedTypeReference<List<OffenderId>>() {}

    fun getOffendersByPrison(prisonId: String): List<OffenderBooking>? {
        return prisonWebClient.get()
            .uri("/api/bookings?query=agencyId:eq:'$prisonId'")
            .header("Page-Limit", "1000")
            .retrieve()
            .bodyToMono(prisoners)
            .block(offenderTimeout.multipliedBy(12))
    }

    fun getOffendersIds(page: Int = 0, size: Int = 10): List<OffenderId>? {
        return prisonWebClient.get()
            .uri("/api/offenders/ids")
            .header("Page-Offset", page.toString())
            .header("Page-Limit", size.toString())
            .retrieve()
            .bodyToMono(ids)
            .block(offenderTimeout.multipliedBy(24))
    }

  fun getOffender(bookingId: Long): OffenderBooking? {
    return prisonWebClient.get()
        .uri("/api/bookings/$bookingId")
        .retrieve()
        .bodyToMono(OffenderBooking::class.java)
        .block(offenderTimeout)
  }

  fun getOffender(nomsId: String): OffenderBooking? {
    return prisonWebClient.get()
        .uri("/api/bookings/offenderNo/$nomsId")
        .retrieve()
        .bodyToMono(OffenderBooking::class.java)
        .block(offenderTimeout)
  }
}

data class OffenderId (
    val offenderNumber: String
)

data class OffenderBooking (
    val offenderNo: String,
    val bookingId: Long,
    val bookingNo: String?,
    val firstName: String,
    val lastName: String,
    val dateOfBirth: LocalDate,
    val agencyId: String?,
    val active: Boolean
)