package com.emofah.agencies.stock

sealed class StockCountRpcOutcome {
    data class CommittedOnline(
        val sessionId: String,
        val alreadyCommitted: Boolean = false,
    ) : StockCountRpcOutcome()

    data class Rejected(val message: String) : StockCountRpcOutcome()
    data class AuthRequired(val message: String) : StockCountRpcOutcome()
    data class RpcMissing(val message: String) : StockCountRpcOutcome()
    data class NetworkOrTransientError(val message: String) : StockCountRpcOutcome()
}

interface StockCountGateway {
    suspend fun submitStockCount(draft: StockCountSessionDraft): StockCountRpcOutcome
}
