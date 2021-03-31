// Copyright (c) 2013 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

/**
 *
 * Modifications: Alex Disler (alexdisler.com)
 * github.com/alexdisler/cordova-plugin-inapppurchase
 *
 */

package com.alexdisler.inapppurchases;

import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaWebView;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class InAppBillingV6 extends CordovaPlugin {

  public static final int BILLING_API_VERSION = 6;

  protected static final String TAG = "google.payments";

  public static final int OK = 0;
  public static final int INVALID_ARGUMENTS = -1;
  public static final int UNABLE_TO_INITIALIZE = -2;
  public static final int BILLING_NOT_INITIALIZED = -3;
  public static final int UNKNOWN_ERROR = -4;
  public static final int USER_CANCELLED = -5;
  public static final int BAD_RESPONSE_FROM_SERVER = -6;
  public static final int VERIFICATION_FAILED = -7;
  public static final int ITEM_UNAVAILABLE = -8;
  public static final int ITEM_ALREADY_OWNED = -9;
  public static final int ITEM_NOT_OWNED = -10;
  public static final int CONSUME_FAILED = -11;

  public static final int PURCHASE_PURCHASED = 0;
  public static final int PURCHASE_CANCELLED = 1;
  public static final int PURCHASE_REFUNDED = 2;

  private IabHelper iabHelper = null;
  boolean billingInitialized = false;
  AtomicInteger orderSerial = new AtomicInteger(0);

  private JSONObject manifestObject = null;

  private JSONObject getManifestContents() {
    if (manifestObject != null) return manifestObject;

    InputStream is;
    try {
      is = getManifestFileInputStream("wwww");
      if (is == null) {
        is = getManifestFileInputStream("public");
      }
      if (is != null) {
        Scanner s = new Scanner(is).useDelimiter("\\A");
        String manifestString = s.hasNext() ? s.next() : "";
        Log.d(TAG, "manifest:" + manifestString);
        manifestObject = new JSONObject(manifestString);
      } else {
        manifestObject = null;
      }
    } catch (JSONException e) {
      Log.d(TAG, "Unable to parse manifest file:" + e.toString());
      manifestObject = null;
    }
    return manifestObject;
  }

  /**
   * Load manifest file from assets by given path.
   *
   * In Cordova manifest file should be placed on path 'www/manifest.json'.
   * In Capacitor the path should be 'public/manifest.json'
   *
   * @param path should contain path of manifest.json file (without '/manifest.json').
   * @return InputStream if manifest file is loaded, null otherwise.
   */
  private InputStream getManifestFileInputStream(String path) {
    InputStream inputStream = null;
    Context context = this.cordova.getActivity();
    try {
      inputStream = context.getAssets().open(path + "/manifest.json");
    } catch (IOException e) {
      Log.d(TAG, "Can not load manifest file on path: " + path + "/manifest.json");
    }
    return inputStream;
  }

  protected String getBase64EncodedPublicKey() {
    JSONObject manifestObject = getManifestContents();
    if (manifestObject != null) {
      return manifestObject.optString("play_store_key");
    }
    return null;
  }

  protected boolean shouldSkipPurchaseVerification() {
    JSONObject manifestObject = getManifestContents();
    if (manifestObject != null) {
      return manifestObject.optBoolean("skip_purchase_verification");
    }
    return false;
  }

  protected boolean initializeBillingHelper() {
    if (iabHelper != null) {
      Log.d(TAG, "Billing already initialized");
      return true;
    }
    Context context = this.cordova.getActivity();
    String base64EncodedPublicKey = getBase64EncodedPublicKey();
    boolean skipPurchaseVerification = shouldSkipPurchaseVerification();
    if (base64EncodedPublicKey != null) {
      iabHelper = new IabHelper(context, base64EncodedPublicKey);
      iabHelper.setSkipPurchaseVerification(skipPurchaseVerification);
      billingInitialized = false;
      return true;
    }
    Log.d(TAG, "Unable to initialize billing");
    return false;
  }

  @Override
  public void initialize(CordovaInterface cordova, CordovaWebView webView) {
    super.initialize(cordova, webView);
    initializeBillingHelper();
  }

  protected JSONObject makeError(String message) {
    return makeError(message, null, null, null);
  }

  protected JSONObject makeError(String message, Integer resultCode) {
    return makeError(message, resultCode, null, null);
  }

  protected JSONObject makeError(String message, Integer resultCode, IabResult result) {
    return makeError(message, resultCode, result.getMessage(), result.getResponse());
  }

  protected JSONObject makeError(String message, Integer resultCode, String text, Integer response) {
    if (message != null) {
      Log.d(TAG, "Error: " + message);
    }
    JSONObject error = new JSONObject();
    try {
      if (resultCode != null) {
        error.put("code", (int)resultCode);
      }
      if (message != null) {
        error.put("message", message);
      }
      if (text != null) {
        error.put("text", text);
      }
      if (response != null) {
        error.put("response", response);
      }
    } catch (JSONException e) {}
    return error;
  }

  @Override
  public boolean execute(String action, final JSONArray args, final CallbackContext callbackContext) {
    Log.d(TAG, "executing on android");
    if ("init".equals(action)) {
      return init(args, callbackContext);
    } else if ("buy".equals(action)) {
      return buy(args, callbackContext);
    } else if ("subscribe".equals(action)) {
      return subscribe(args, callbackContext);
    } else if ("consumePurchase".equals(action)) {
      return consumePurchase(args, callbackContext);
    } else if ("getSkuDetails".equals(action)) {
      return getSkuDetails(args, callbackContext);
    } else if ("restorePurchases".equals(action)) {
      return restorePurchases(args, callbackContext);
    } else if ("getFormattedPrice".equals(action)) {
      return getFormattedPrice(args, callbackContext);
    }
    return false;
  }

  protected boolean init(final JSONArray args, final CallbackContext callbackContext) {
    if (billingInitialized) {
      Log.d(TAG, "Billing already initialized");
      callbackContext.success();
    } else if (iabHelper == null) {
      callbackContext.error(makeError("Billing cannot be initialized", UNABLE_TO_INITIALIZE));
    } else {
      iabHelper.startSetup(result -> {
        if (!result.isSuccess()) {
          callbackContext.error(makeError("Unable to initialize billing: " + result.toString(), UNABLE_TO_INITIALIZE, result));
        } else {
          Log.d(TAG, "Billing initialized");
          billingInitialized = true;
          callbackContext.success();
        }
      });
    }
    return true;
  }

  protected boolean runPayment(final JSONArray args, final CallbackContext callbackContext, boolean subscribe) {
    final String sku;
    String developerPayload = "";
    try {
      sku = args.getString(0);
      if (args.length() > 1) {
        developerPayload = args.getString(1);
      }
    } catch (JSONException e) {
      callbackContext.error(makeError("Invalid SKU", INVALID_ARGUMENTS));
      return false;
    }

    final Bundle extraParams;
    try {
      JSONObject arg1 = args.optJSONObject(1);
      String accountId = "";
      boolean replaceSkusProration = true;
      JSONArray skusToReplaceJson = new JSONArray();
      if (arg1 != null) {
        accountId = arg1.optString("accountId");
        replaceSkusProration = arg1.optBoolean("replaceSkusProration", true);
        skusToReplaceJson = arg1.optJSONArray("skusToReplace");
        if (skusToReplaceJson == null) {
          skusToReplaceJson = new JSONArray();
        }
      }
      List<String> ownedSkus;
      try {
        Inventory inventory = iabHelper.queryInventory(true, convertJsonArrayToList(skusToReplaceJson));
        ownedSkus = inventory.getAllOwnedSkus();
      } catch (IabException iax) {
        callbackContext.error(makeError("Unable to retrieve owned products", BAD_RESPONSE_FROM_SERVER));
        return false;
      }

      // skusToReplace intent parameter required only passing a currently subscribed plan.
      ArrayList<String> skusToReplace = new ArrayList<>();
      for (int i = 0; i < skusToReplaceJson.length(); i++) {
        String skuToReplace = skusToReplaceJson.getString(i);
        if (!sku.equals(skuToReplace) && ownedSkus.contains(skuToReplace)) {
          skusToReplace.add(skuToReplace);
        }
      }
      extraParams = new Bundle();
      if (!accountId.isEmpty())
        extraParams.putString("accountId", accountId);
      extraParams.putBoolean("replaceSkusProration", replaceSkusProration);
      extraParams.putStringArrayList("skusToReplace", skusToReplace);
    } catch (JSONException e) {
      callbackContext.error(makeError("Invalid extraParams", INVALID_ARGUMENTS));
      return false;
    }

    if (iabHelper == null || !billingInitialized) {
      callbackContext.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED));
      return false;
    }
    final Activity cordovaActivity = this.cordova.getActivity();
    int newOrder = orderSerial.getAndIncrement();
    this.cordova.setActivityResultCallback(this);
    IabHelper.OnIabPurchaseFinishedListener oipfl = (result, purchase) -> {
      if (result.isFailure()) {
        int response = result.getResponse();
        if (response == IabHelper.IABHELPER_BAD_RESPONSE || response == IabHelper.IABHELPER_UNKNOWN_ERROR) {
          callbackContext.error(makeError("Could not complete purchase", BAD_RESPONSE_FROM_SERVER, result));
        } else if (response == IabHelper.IABHELPER_VERIFICATION_FAILED) {
          callbackContext.error(makeError("Could not complete purchase", BAD_RESPONSE_FROM_SERVER, result));
        } else if (response == IabHelper.IABHELPER_USER_CANCELLED) {
          callbackContext.error(makeError("Purchase Cancelled", USER_CANCELLED, result));
        } else if (response == IabHelper.BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED) {
          callbackContext.error(makeError("Item already owned", ITEM_ALREADY_OWNED, result));
        } else {
          callbackContext.error(makeError("Error completing purchase: " + response, UNKNOWN_ERROR, result));
        }
      } else {
        try {
          JSONObject pluginResponse = new JSONObject();
          pluginResponse.put("orderId", purchase.getOrderId());
          pluginResponse.put("packageName", purchase.getPackageName());
          pluginResponse.put("productId", purchase.getSku());
          pluginResponse.put("purchaseTime", purchase.getPurchaseTime());
          pluginResponse.put("purchaseState", purchase.getPurchaseState());
          pluginResponse.put("purchaseToken", purchase.getToken());
          pluginResponse.put("signature", purchase.getSignature());
          pluginResponse.put("type", purchase.getItemType());
          pluginResponse.put("receipt", purchase.getOriginalJson());
          callbackContext.success(pluginResponse);
        } catch (JSONException e) {
          callbackContext.error("Purchase succeeded but success handler failed");
        }
      }
    };
    if(subscribe){
      iabHelper.launchSubscriptionPurchaseFlow(cordovaActivity, sku, newOrder, oipfl, developerPayload, extraParams);
    } else {
      iabHelper.launchPurchaseFlow(cordovaActivity, sku, newOrder, oipfl, developerPayload, extraParams);
    }
    return true;
  }

  protected boolean subscribe(final JSONArray args, final CallbackContext callbackContext) {
    return runPayment(args, callbackContext, true);
  }

  protected boolean buy(final JSONArray args, final CallbackContext callbackContext) {
    return runPayment(args, callbackContext, false);
  }

  protected boolean consumePurchase(final JSONArray args, final CallbackContext callbackContext) {
    final Purchase purchase;
    try {
      String type = args.getString(0);
      String receipt = args.getString(1);
      String signature = args.getString(2);
      purchase = new Purchase(type, receipt, signature);
    } catch (JSONException e) {
      callbackContext.error(makeError("Unable to parse purchase token", INVALID_ARGUMENTS));
      return false;
    }
    if (purchase == null) {
      callbackContext.error(makeError("Unrecognized purchase token", INVALID_ARGUMENTS));
      return false;
    }
    if (iabHelper == null || !billingInitialized) {
      callbackContext.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED));
      return false;
    }
    iabHelper.consumeAsync(purchase, (purchase1, result) -> {
      if (result.isFailure()) {
        int response = result.getResponse();
        if (response == IabHelper.BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED) {
          callbackContext.error(makeError("Error consuming purchase", ITEM_NOT_OWNED, result));
        } else {
          callbackContext.error(makeError("Error consuming purchase", CONSUME_FAILED, result));
        }
      } else {
        try {
          JSONObject pluginResponse = new JSONObject();
          pluginResponse.put("transactionId", purchase1.getOrderId());
          pluginResponse.put("productId", purchase1.getSku());
          pluginResponse.put("token", purchase1.getToken());
          callbackContext.success(pluginResponse);
        } catch (JSONException e) {
          callbackContext.error("Consume succeeded but success handler failed");
        }
      }
    });
    return true;
  }

  protected boolean getFormattedPrice(final JSONArray args, final CallbackContext callbackContext) {
    try {
      String price = args.getString(0);
      NumberFormat format = NumberFormat.getCurrencyInstance();
      format.setCurrency(Currency.getInstance(Locale.getDefault()));
      String result;
      if (price.contains(".") || price.contains(",")) {
        result = format.format(Double.parseDouble(price));
      } else {
        result = format.format(Long.parseLong(price));
      }
      JSONObject pluginResponse = new JSONObject();
      pluginResponse.put("formattedPrice", result);
      callbackContext.success(pluginResponse);
    } catch (JSONException e) {
      callbackContext.error(makeError("No defined price in arguments", INVALID_ARGUMENTS));
      return false;
    } catch (NumberFormatException e) {
      callbackContext.error(makeError("Can not parse given argument", INVALID_ARGUMENTS));
      return false;
    } catch (NullPointerException e) {
      callbackContext.error(makeError("Locale or its country code is null"));
      return false;
    } catch (IllegalArgumentException e) {
      callbackContext.error(makeError("Country of the given Locale is not a supported ISO 3166 country code"));
      return false;
    }
    return true;
  }

  protected boolean getSkuDetails(final JSONArray args, final CallbackContext callbackContext) {
    final List<String> moreSkus = new ArrayList<String>();
    try {
      for (int i = 0; i < args.length(); i++) {
        moreSkus.add(args.getString(i));
        Log.d(TAG, "get sku:" + args.getString(i));
      }
    } catch (JSONException e) {
      callbackContext.error(makeError("Invalid SKU", INVALID_ARGUMENTS));
      return false;
    }
    if (iabHelper == null || !billingInitialized) {
      callbackContext.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED));
      return false;
    }
    iabHelper.queryInventoryAsync(true, moreSkus, (result, inventory) -> {
      if (result.isFailure()) {
        callbackContext.error("Error retrieving SKU details");
        return;
      }
      JSONArray response = new JSONArray();
      try {
        for (String sku : moreSkus) {
          SkuDetails skuDetails = inventory.getSkuDetails(sku);
          if (skuDetails != null) {
            JSONObject detailsJson = new JSONObject();
            detailsJson.put("productId", skuDetails.getSku());
            detailsJson.put("title", skuDetails.getTitle());
            detailsJson.put("description", skuDetails.getDescription());
            detailsJson.put("priceAsDecimal", skuDetails.getPriceAsDecimal());
            detailsJson.put("price", skuDetails.getPrice());
            detailsJson.put("priceRaw", skuDetails.getPriceRaw());
            detailsJson.put("currency", skuDetails.getPriceCurrency());
            detailsJson.put("country", "-");
            detailsJson.put("type", skuDetails.getType());
            detailsJson.put("currency", skuDetails.getPriceCurrency());
            response.put(detailsJson);
          }
        }
      } catch (JSONException e) {
        callbackContext.error(e.getMessage());
      }
      callbackContext.success(response);
    });
    return true;
  }

  protected boolean restorePurchases(final JSONArray args, final CallbackContext callbackContext) {
    if (iabHelper == null || !billingInitialized) {
      callbackContext.error(makeError("Billing is not initialized", BILLING_NOT_INITIALIZED));
    } else {
      iabHelper.queryInventoryAsync((result, inventory) -> {
        if (result.isFailure()) {
          callbackContext.error("Error retrieving purchase details");
          return;
        }
        JSONArray response = new JSONArray();
        try {
          for (Purchase purchase : inventory.getAllPurchases()) {
            if (purchase != null) {
              JSONObject detailsJson = new JSONObject();
              detailsJson.put("orderId", purchase.getOrderId());
              detailsJson.put("packageName", purchase.getPackageName());
              detailsJson.put("productId", purchase.getSku());
              detailsJson.put("purchaseTime", purchase.getPurchaseTime());
              detailsJson.put("purchaseState", purchase.getPurchaseState());
              detailsJson.put("purchaseToken", purchase.getToken());
              detailsJson.put("signature", purchase.getSignature());
              detailsJson.put("type", purchase.getItemType());
              detailsJson.put("receipt", purchase.getOriginalJson());
              response.put(detailsJson);
            }
          }
        } catch (JSONException e) {
          callbackContext.error(e.getMessage());
        }
        callbackContext.success(response);
      });
    }
    return true;
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    if (!iabHelper.handleActivityResult(requestCode, resultCode, intent)) {
      super.onActivityResult(requestCode, resultCode, intent);
    }
  }


  @Override
  public void onDestroy() {
    if (iabHelper != null) iabHelper.dispose();
    iabHelper = null;
    billingInitialized = false;
  }

  private List<String> convertJsonArrayToList(JSONArray jsonArray) throws JSONException {
    List<String> list = new ArrayList<>();
    for (int i=0; i<jsonArray.length(); i++) {
      list.add( jsonArray.getString(i) );
    }
    return list;
  }
}
