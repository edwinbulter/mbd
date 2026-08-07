package com.mbd.admin.repository

import com.mbd.admin.entity.SystemConfig
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SystemConfigRepository : JpaRepository<SystemConfig, Long> {
    fun findByKey(key: String): SystemConfig?
}
