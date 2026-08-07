package com.mbd.account.repository

import com.mbd.account.entity.Transaction
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TransactionRepository : JpaRepository<Transaction, Long> {
    fun findByAccountId(accountId: Long): List<Transaction>
}
