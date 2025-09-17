package com.talentodev.talktome.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.android.billingclient.api.*
import com.android.billingclient.api.BillingClient

import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.ProductDetails



class BillingManager(
    private val context: Context,
    private val productId: String,
    private val onPurchaseComplete: () -> Unit
) : PurchasesUpdatedListener {

    private var connectionCallback: ((Boolean) -> Unit)? = null
    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()


    private var isReady = false

    fun startConnection(callback: (Boolean) -> Unit) {
        connectionCallback = callback

        if (billingClient.isReady) {
            callback(true)
            return
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    isReady = true
                    Log.d("BillingManager", "Billing connected")
                    callback(true)
                    checkActivePurchases { isSubscribed ->
                        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("is_subscribed", isSubscribed).apply()
                        if (isSubscribed) onPurchaseComplete()
                    }
                } else {
                    Log.e("BillingManager", "Billing setup failed: ${billingResult.debugMessage}")
                    callback(false)
                    showToast("Erro ao conectar com a loja")
                }
            }

            override fun onBillingServiceDisconnected() {
                isReady = false
                Log.w("BillingManager", "Billing service disconnected")
                Handler(Looper.getMainLooper()).postDelayed({
                    startConnection { connected ->
                        if (!connected) {
                            showToast("Falha na reconexão com a loja")
                        }
                    }
                }, 2000)
            }
        })
    }

    fun launchPurchase(activity: Activity) {
        if (!isReady) {
            showToast("Conectando à loja...")
            startConnection { connected ->
                if (connected) launchPurchase(activity)
                else showToast("Erro ao conectar à loja")
            }
            return
        }

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                showToast("Erro ao buscar produto")
                Log.e("BillingManager", "Erro ao buscar produtos: ${billingResult.debugMessage}")
                return@queryProductDetailsAsync
            }

            val productDetailsList = queryProductDetailsResult.productDetailsList
            val productDetails = productDetailsList.firstOrNull()
            if (productDetails == null) {
                showToast("Produto não encontrado")
                Log.e("BillingManager", "Produto $productId não encontrado")
                return@queryProductDetailsAsync
            }

            val offerDetails = productDetails.subscriptionOfferDetails?.firstOrNull()
            if (offerDetails == null) {
                showToast("Oferta indisponível")
                Log.e("BillingManager", "Nenhuma oferta disponível para o produto $productId")
                return@queryProductDetailsAsync
            }

            val offerToken = offerDetails.offerToken
            if (offerToken.isNullOrEmpty()) {
                showToast("Token da oferta não encontrado")
                Log.e("BillingManager", "Token da oferta nulo para $productId")
                return@queryProductDetailsAsync
            }

            val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .setOfferToken(offerToken)
                .build()

            val billingFlowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productDetailsParams))
                .build()

            val response = billingClient.launchBillingFlow(activity, billingFlowParams)
            if (response.responseCode != BillingClient.BillingResponseCode.OK) {
                showToast("Falha ao iniciar compra")
                Log.e("BillingManager", "Falha ao iniciar fluxo de compra: ${response.debugMessage}")
            }
        }
    }


    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        val sharedPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.products.contains(productId) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    ) {
                        acknowledgePurchaseIfNeeded(purchase)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                showToast("Compra cancelada")
                sharedPrefs.edit().putBoolean("is_subscribed", false).apply()
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                showToast("Você já possui essa assinatura")
                sharedPrefs.edit().putBoolean("is_subscribed", true).apply()
                onPurchaseComplete()
            }
            else -> {
                Log.e("BillingManager", "Erro de compra: ${billingResult.debugMessage}")
                showToast("Erro de compra")
            }
        }
    }

    fun checkActivePurchases(callback: (Boolean) -> Unit) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e("BillingManager", "Erro ao verificar compras: ${billingResult.debugMessage}")
                callback(false)
                return@queryPurchasesAsync
            }

            val hasActive = purchases.any { purchase ->
                purchase.products.contains(productId) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        purchase.isAcknowledged
            }
            callback(hasActive)
        }
    }

    private fun acknowledgePurchaseIfNeeded(purchase: Purchase) {
        if (!purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()

            billingClient.acknowledgePurchase(params) { billingResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d("BillingManager", "Compra reconhecida")
                    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        .edit().putBoolean("is_subscribed", true).apply()
                    onPurchaseComplete()
                } else {
                    Log.e("BillingManager", "Erro ao reconhecer compra: ${billingResult.debugMessage}")
                    showToast("Erro ao confirmar compra")
                }
            }
        } else {
            context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("is_subscribed", true).apply()
            onPurchaseComplete()
        }
    }

    fun loadLocalizedPrice(callback: (String) -> Unit) {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && !queryProductDetailsResult.productDetailsList.isNullOrEmpty()) {
                val productDetails = queryProductDetailsResult.productDetailsList[0]
                val offerDetailsList = productDetails.subscriptionOfferDetails

                if (!offerDetailsList.isNullOrEmpty()) {
                    val pricingPhase = offerDetailsList[0]
                        .pricingPhases
                        ?.pricingPhaseList
                        ?.firstOrNull()

                    if (pricingPhase != null) {
                        val localizedPrice = pricingPhase.formattedPrice
                        callback(localizedPrice)
                        return@queryProductDetailsAsync
                    }
                }
            }

            Log.e("BillingManager", "Erro ao buscar o preço: ${billingResult.debugMessage}")
            callback("Indisponível")
        }
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    fun endConnection() {
        billingClient.endConnection()
        isReady = false
    }
}
