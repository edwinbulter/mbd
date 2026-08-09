package com.mbd.portfolio

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.openfeign.EnableFeignClients
import org.springframework.kafka.annotation.EnableKafka

@SpringBootApplication
@EnableKafka
@EnableFeignClients
class PortfolioServiceApplication

fun main(args: Array<String>) {
    runApplication<PortfolioServiceApplication>(*args)
}
