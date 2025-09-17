package com.talentodev.financeiro.billing;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.android.billingclient.api.*;

import java.util.List;

public class BillingManager implements PurchasesUpdatedListener {

    private final Context context;
    private final String productId;
    private final Runnable onPurchaseComplete;
    private BillingClient billingClient;
    private boolean isReady = false;
    private Callback connectionCallback;

    public interface Callback {
        void onResult(boolean success);
    }

    public BillingManager(Context context, String productId, Runnable onPurchaseComplete) {
        this.context = context;
        this.productId = productId;
        this.onPurchaseComplete = onPurchaseComplete;

        billingClient = BillingClient.newBuilder(context)
                .setListener(this)
                .enablePendingPurchases(
                        PendingPurchasesParams.newBuilder()
                                .enableOneTimeProducts()
                                .build()
                )
                .build();
    }
    private SharedPreferences getPrefs() {
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
    }
    // Interface para o callback de conexão
    public interface BillingConnectionCallback {
        void onConnectionResult(boolean isConnected);
    }

    // Interface para o callback de compra ativa
    public interface ActivePurchaseCallback {
        void onResult(boolean isSubscribed);
    }

    public void startConnection(Callback callback) {
        this.connectionCallback = callback;

        if (billingClient.isReady()) {
            callback.onResult(true);
            return;
        }

        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    isReady = true;
                    Log.d("BillingManager", "Billing connected");
                    callback.onResult(true);
                    checkActivePurchases(isSubscribed -> {
                        getPrefs().edit().putBoolean("is_subscribed", isSubscribed).apply();
                        if (isSubscribed) onPurchaseComplete.run();
                    });
                } else {
                    Log.e("BillingManager", "Billing setup failed: " + billingResult.getDebugMessage());
                    callback.onResult(false);
                    showToast("Erro ao conectar com a loja");
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                isReady = false;
                Log.w("BillingManager", "Billing service disconnected");
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    startConnection(connected -> {
                        if (!connected) {
                            showToast("Falha na reconexão com a loja");
                        }
                    });
                }, 2000);
            }
        });
    }

    public void launchPurchase(Activity activity) {
        if (!isReady) {
            showToast("Conectando à loja...");
            startConnection(connected -> {
                if (connected) launchPurchase(activity);
                else showToast("Erro ao conectar à loja");
            });
            return;
        }

        QueryProductDetailsParams.Product product =
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build();

        QueryProductDetailsParams params =
                QueryProductDetailsParams.newBuilder()
                        .setProductList(java.util.Collections.singletonList(product))
                        .build();

        billingClient.queryProductDetailsAsync(params, (billingResult, productDetailsResult) -> {
            if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                showToast("Erro ao buscar produto");
                Log.e("BillingManager", "Erro ao buscar produtos: " + billingResult.getDebugMessage());
                return;
            }

            List<ProductDetails> productDetailsList = productDetailsResult.getProductDetailsList();
            if (productDetailsList == null || productDetailsList.isEmpty()) {
                showToast("Produto não encontrado");
                Log.e("BillingManager", "Produto " + productId + " não encontrado");
                return;
            }

            ProductDetails productDetails = productDetailsList.get(0);
            List<ProductDetails.SubscriptionOfferDetails> offerDetailsList = productDetails.getSubscriptionOfferDetails();
            if (offerDetailsList == null || offerDetailsList.isEmpty()) {
                showToast("Oferta indisponível");
                Log.e("BillingManager", "Nenhuma oferta disponível para o produto " + productId);
                return;
            }

            ProductDetails.SubscriptionOfferDetails offerDetails = offerDetailsList.get(0);
            String offerToken = offerDetails.getOfferToken();
            if (offerToken == null || offerToken.isEmpty()) {
                showToast("Token da oferta não encontrado");
                Log.e("BillingManager", "Token da oferta nulo para " + productId);
                return;
            }

            BillingFlowParams.ProductDetailsParams productDetailsParams =
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .setOfferToken(offerToken)
                            .build();

            BillingFlowParams billingFlowParams =
                    BillingFlowParams.newBuilder()
                            .setProductDetailsParamsList(java.util.Collections.singletonList(productDetailsParams))
                            .build();

            BillingResult response = billingClient.launchBillingFlow(activity, billingFlowParams);
            if (response.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                showToast("Falha ao iniciar compra");
                Log.e("BillingManager", "Falha ao iniciar fluxo de compra: " + response.getDebugMessage());
            }
        });
    }

    @Override
    public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> purchases) {
        SharedPreferences prefs = getPrefs();

        switch (billingResult.getResponseCode()) {
            case BillingClient.BillingResponseCode.OK:
                if (purchases != null) {
                    for (Purchase purchase : purchases) {
                        if (purchase.getProducts().contains(productId)
                                && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                            acknowledgePurchaseIfNeeded(purchase);
                        }
                    }
                }
                break;
            case BillingClient.BillingResponseCode.USER_CANCELED:
                showToast("Compra cancelada");
                prefs.edit().putBoolean("is_subscribed", false).apply();
                break;
            case BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED:
                showToast("Você já possui essa assinatura");
                prefs.edit().putBoolean("is_subscribed", true).apply();
                onPurchaseComplete.run();
                break;
            default:
                Log.e("BillingManager", "Erro de compra: " + billingResult.getDebugMessage());
                showToast("Erro de compra");
                break;
        }
    }

    public void checkActivePurchases(ActivePurchaseCallback callback) {
        QueryPurchasesParams params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build();

        billingClient.queryPurchasesAsync(params, (billingResult, purchases) -> {
            if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                Log.e("BillingManager", "Erro ao verificar compras: " + billingResult.getDebugMessage());
                callback.onResult(false);
                return;
            }

            boolean hasActive = false;
            for (Purchase purchase : purchases) {
                if (purchase.getProducts().contains(productId)
                        && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED
                        && purchase.isAcknowledged()) {
                    hasActive = true;
                    break;
                }
            }
            callback.onResult(hasActive);
        });
    }


    private void acknowledgePurchaseIfNeeded(Purchase purchase) {
        if (!purchase.isAcknowledged()) {
            AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.getPurchaseToken())
                    .build();

            billingClient.acknowledgePurchase(params, billingResult -> {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    Log.d("BillingManager", "Compra reconhecida");
                    getPrefs().edit().putBoolean("is_subscribed", true).apply();
                    onPurchaseComplete.run();
                } else {
                    Log.e("BillingManager", "Erro ao reconhecer compra: " + billingResult.getDebugMessage());
                    showToast("Erro ao confirmar compra");
                }
            });
        } else {
            getPrefs().edit().putBoolean("is_subscribed", true).apply();
            onPurchaseComplete.run();
        }
    }

    public void loadLocalizedPrice(PriceCallback callback) {
        QueryProductDetailsParams.Product product =
                QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build();

        QueryProductDetailsParams params =
                QueryProductDetailsParams.newBuilder()
                        .setProductList(java.util.Collections.singletonList(product))
                        .build();

        billingClient.queryProductDetailsAsync(params, (billingResult, result) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK &&
                    result.getProductDetailsList() != null && !result.getProductDetailsList().isEmpty()) {
                ProductDetails productDetails = result.getProductDetailsList().get(0);
                List<ProductDetails.SubscriptionOfferDetails> offerDetailsList = productDetails.getSubscriptionOfferDetails();

                if (offerDetailsList != null && !offerDetailsList.isEmpty()) {
                    ProductDetails.PricingPhase pricingPhase = offerDetailsList.get(0)
                            .getPricingPhases()
                            .getPricingPhaseList()
                            .get(0);
                    String localizedPrice = pricingPhase.getFormattedPrice();
                    callback.onPriceReady(localizedPrice);
                    return;
                }
            }

            Log.e("BillingManager", "Erro ao buscar o preço: " + billingResult.getDebugMessage());
            callback.onPriceReady("Indisponível");
        });
    }

    public interface PriceCallback {
        void onPriceReady(String price);
    }

    private void showToast(final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void endConnection() {
        billingClient.endConnection();
        isReady = false;
    }

    public boolean isReady() {
        return isReady;
    }
}