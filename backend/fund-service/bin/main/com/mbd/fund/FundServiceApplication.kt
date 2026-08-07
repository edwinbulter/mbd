package com.mbd.fund

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.kafka.annotation.EnableKafka

@SpringBootApplication
@EnableKafka
class FundServiceApplication

fun main(args: Array<String>) {
    runApplication<FundServiceApplication>(*args)
}
