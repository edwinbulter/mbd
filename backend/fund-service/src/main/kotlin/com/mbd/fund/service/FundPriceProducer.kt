package com.mbd.fund.service

import com.mbd.shared.dto.FundPriceUpdate
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class FundPriceProducer(
    private val kafkaTemplate: KafkaTemplate<String, FundPriceUpdate>
) {
    fun publishPriceUpdate(update: FundPriceUpdate) {
        kafkaTemplate.send("fund-price-updates", update.fundId.toString(), update)
    }
}
