package com.mbd.portfolio.client

import com.mbd.shared.dto.FundDto
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

@FeignClient(name = "fund-service", url = "http://fund-service.mbd.svc.cluster.local:8080")
interface FundClient {
    @GetMapping("/api/funds/{fundId}")
    fun getFund(@PathVariable fundId: Long): FundDto?
}
