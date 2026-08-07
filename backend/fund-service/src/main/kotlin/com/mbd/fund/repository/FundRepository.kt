package com.mbd.fund.repository

import com.mbd.fund.entity.Fund
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface FundRepository : JpaRepository<Fund, Long> {
    fun findByIsin(isin: String): Fund?
}
