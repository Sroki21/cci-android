package pl.sroki.cci.android.data

import pl.sroki.cci.android.data.datasource.remote.ProducerApiService
import pl.sroki.cci.android.model.Producer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProducersRepository @Inject constructor(private val producerApiService: ProducerApiService) {
    suspend fun getProducers(): List<Producer> = producerApiService.getAll()
}
