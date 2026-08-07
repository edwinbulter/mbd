package com.mbd.account

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.openfeign.EnableFeignClients

@SpringBootApplication
@EnableFeignClients
class AccountServiceApplication

fun main(args: Array<String>) {
    runApplication<AccountServiceApplication>(*args)
}
