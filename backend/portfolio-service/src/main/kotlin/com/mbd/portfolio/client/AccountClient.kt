package com.mbd.portfolio.client

import com.mbd.shared.dto.AccountDto
import com.mbd.shared.dto.DepositDto
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

@FeignClient(name = "account-service", url = "http://account-service.mbd.svc.cluster.local:8080")
interface AccountClient {
    @GetMapping("/api/accounts/{accountId}")
    fun getAccount(@PathVariable accountId: Long): AccountDto?

    @PostMapping("/api/accounts/{accountId}/deposit")
    fun updateBalance(@PathVariable accountId: Long, @RequestBody deposit: DepositDto): AccountDto?
}
