package com.mbd.portfolio.repository

import com.mbd.portfolio.entity.Holding
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface HoldingRepository : JpaRepository<Holding, Long> {
    fun findByAccountId(accountId: Long): List<Holding>
    fun findByFundId(fundId: Long): List<Holding>
    fun findByAccountIdAndFundId(accountId: Long, fundId: Long): Holding?
}
