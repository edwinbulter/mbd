package com.mbd.admin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.kafka.annotation.EnableKafka

@SpringBootApplication
@EnableKafka
class AdminServiceApplication

fun main(args: Array<String>) {
    runApplication<AdminServiceApplication>(*args)
}
