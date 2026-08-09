package com.mbd.fund

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableKafka
@EnableScheduling
class FundServiceApplication

fun main(args: Array<String>) {
    runApplication<FundServiceApplication>(*args)
}
