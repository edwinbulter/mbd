package com.mbd.portfolio

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.kafka.annotation.EnableKafka

@SpringBootApplication
@EnableKafka
class PortfolioServiceApplication

fun main(args: Array<String>) {
    runApplication<PortfolioServiceApplication>(*args)
}
