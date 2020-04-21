package uk.org.justice.digital.hmpps.prisonersearch.config

import com.google.gson.*
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.lang.reflect.Type
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Configuration
class GsonConfig() {

    @Bean
    fun gson() : Gson {
       return GsonBuilder().setPrettyPrinting()
                .registerTypeAdapter(LocalDate::class.java, LocalDateAdapter())
                .create();
    }

    internal class LocalDateAdapter : JsonSerializer<LocalDate?>, JsonDeserializer<LocalDate?> {
        override fun serialize(src: LocalDate?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
            return JsonPrimitive(src?.format(DateTimeFormatter.ISO_LOCAL_DATE))
        }

        override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): LocalDate? {
            return LocalDate.parse(json?.asJsonPrimitive?.asString);
        }
    }

}