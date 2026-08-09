package com.mbd.portfolio.repository

import com.mbd.portfolio.entity.PortfolioValueSnapshot
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface PortfolioValueSnapshotRepository : JpaRepository<PortfolioValueSnapshot, Long> {
    fun findByAccountIdOrderByTimestampDesc(accountId: Long, pageable: Pageable): List<PortfolioValueSnapshot>
}
