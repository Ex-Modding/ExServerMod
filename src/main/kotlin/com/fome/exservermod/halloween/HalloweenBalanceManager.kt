package com.fome.exservermod.halloween

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HalloweenBalanceManager(
    private val storageFile: File,
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val balances: ConcurrentHashMap<UUID, Int> = ConcurrentHashMap()
    private val isDirty: AtomicBoolean = AtomicBoolean(false)
    private val ioExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Halloween-Balance-IO").apply { isDaemon = true }
        }

    init {
        load()
    }

    fun markDirty() {
        isDirty.set(true)
    }

    fun getBalance(uuid: UUID): Int = balances.getOrDefault(uuid, 0)

    fun addCandies(
        player: ServerPlayer,
        amount: Int,
        sendActionBar: Boolean = true,
    ): Int {
        if (amount <= 0) return getBalance(player.uuid)
        val newBalance = balances.compute(player.uuid) { _, current -> (current ?: 0) + amount } ?: amount
        markDirty()
        if (sendActionBar) {
            sendBalanceActionBar(player, newBalance)
        }
        return newBalance
    }

    fun addCandies(
        uuid: UUID,
        amount: Int,
    ): Int {
        if (amount <= 0) return getBalance(uuid)
        val newBalance = balances.compute(uuid) { _, current -> (current ?: 0) + amount } ?: amount
        markDirty()
        return newBalance
    }

    fun spendCandies(
        player: ServerPlayer,
        cost: Int,
    ): Boolean {
        if (cost <= 0) return true
        var success = false
        balances.compute(player.uuid) { _, current ->
            val bal = current ?: 0
            if (bal >= cost) {
                success = true
                bal - cost
            } else {
                success = false
                bal
            }
        }
        if (success) {
            markDirty()
            sendBalanceActionBar(player, getBalance(player.uuid))
        }
        return success
    }

    fun removeCandies(
        player: ServerPlayer,
        amount: Int,
    ): Int {
        if (amount <= 0) return 0
        var removed = 0
        balances.compute(player.uuid) { _, current ->
            val bal = current ?: 0
            removed = minOf(amount, bal)
            (bal - removed).coerceAtLeast(0)
        }
        if (removed > 0) {
            markDirty()
            sendBalanceActionBar(player, getBalance(player.uuid))
        }
        return removed
    }

    fun removeCandies(
        uuid: UUID,
        amount: Int,
    ): Int {
        if (amount <= 0) return 0
        var removed = 0
        balances.compute(uuid) { _, current ->
            val bal = current ?: 0
            removed = minOf(amount, bal)
            (bal - removed).coerceAtLeast(0)
        }
        if (removed > 0) {
            markDirty()
        }
        return removed
    }

    fun setCandies(
        uuid: UUID,
        amount: Int,
    ) {
        val sanitized = amount.coerceAtLeast(0)
        balances[uuid] = sanitized
        markDirty()
        save(sync = true)
    }

    fun sendBalanceActionBar(
        player: ServerPlayer,
        balance: Int = getBalance(player.uuid),
        timeFormatted: String? = null,
    ) {
        val message =
            if (timeFormatted != null) {
                Component.translatable("exservermod.halloween.candy_counter_time", balance, timeFormatted)
            } else {
                Component.translatable("exservermod.halloween.candy_counter", balance)
            }
        player.sendSystemMessage(
            message,
            true,
        )
    }

    fun load() {
        if (!storageFile.exists()) return
        try {
            val json = storageFile.readText(Charsets.UTF_8)
            val type = object : TypeToken<Map<String, Int>>() {}.type
            val loaded: Map<String, Int>? = gson.fromJson(json, type)
            balances.clear()
            loaded?.forEach { (k, v) ->
                try {
                    balances[UUID.fromString(k)] = v
                } catch (_: IllegalArgumentException) {
                    // Ignore malformed UUID keys
                }
            }
        } catch (_: Exception) {
            // Keep in-memory balances on read failure
        }
    }

    fun saveIfDirty(sync: Boolean = false) {
        if (isDirty.compareAndSet(true, false)) {
            if (sync) {
                doSave()
            } else {
                ioExecutor.submit { doSave() }
            }
        }
    }

    fun save(sync: Boolean = true) {
        isDirty.set(false)
        if (sync) {
            doSave()
        } else {
            ioExecutor.submit { doSave() }
        }
    }

    private fun doSave() {
        try {
            storageFile.parentFile?.mkdirs()
            val stringKeyMap = balances.mapKeys { it.key.toString() }
            storageFile.writeText(gson.toJson(stringKeyMap), Charsets.UTF_8)
        } catch (_: Exception) {
            // Non-fatal configuration write failure
        }
    }
}
