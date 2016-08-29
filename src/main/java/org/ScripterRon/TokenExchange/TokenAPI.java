/*
 * Copyright 2016 Ronald W Hoffman.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ScripterRon.TokenExchange;

import nxt.http.APIServlet;
import nxt.http.APITag;
import nxt.http.ParameterException;
import nxt.util.Convert;
import nxt.util.JSON;
import nxt.util.Logger;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

/**
 * <p>TokenExchange API
 *
 * <p>The following functions are provided:
 * <ul>
 * <li>blockReceived - Notification that a new Bitcoin block has been received.  The
 * 'id' parameter specifies the block identifier.
 * <li>deleteToken - Delete a token from the database.  The 'id' parameter specifies the
 * token to be deleted.
 * <li>getAddress - Get a new Bitcoin address and associate it with a Nxt account.
 * The 'account' parameter identifies the Nxt account.  The 'publicKey' parameter
 * can be specified to further identify the Nxt account and should be specified for
 * a new Nxt account.
 * <li>getStatus - Returns the current status of the TokenExchange add-on.
 * <li>getTokens - Returns a transaction token list.  The 'height' parameter
 * can be used to specify the starting height, otherwise a height of 0 is used.
 * Transaction tokens at a height greater than the specified height
 * will be returned.  The 'includeExchanged' parameter can be used to return exchanged
 * tokens in addition to unexchanged tokens.
 * <li>resumeSend - Resume sending bitcoins for redeemed tokens and issuing tokens for received
 * bitcoins.
 * <li>suspendSend - Stop sending bitcoins for redeemed tokens and issuing tokens for received
 * bitcoins.
 * <li>transactionReceived -Notification that a new bitcoin transaction has been received.  The
 * 'id' parameter specifies the transaction identifier.
 * </ul>
 */
public class TokenAPI extends APIServlet.APIRequestHandler {

    /** Bitcoin processing lock */
    private static final Object bitcoinLock = new Object();

    /** Processing bitcoin transactions */
    private static volatile boolean processingTransactions = false;

    /**
     * Create the API request handler
     */
    public TokenAPI() {
        super(new APITag[] {APITag.ADDONS}, "function", "id", "includeExchanged", "height", "account", "publicKey");
    }

    /**
     * Process the API request
     *
     * @param   req                 HTTP request
     * @return                      HTTP response
     * @throws  ParameterException  Parameter error detected
     */
    @SuppressWarnings("unchecked")
    @Override
    protected JSONStreamAware processRequest(HttpServletRequest req) throws ParameterException {
        JSONObject response = new JSONObject();
        String function = Convert.emptyToNull(req.getParameter("function"));
        if (function == null) {
            return missing("function");
        }
        switch (function) {
            case "getStatus":
                response.put("exchangeRate", TokenAddon.exchangeRate.toPlainString());
                response.put("currencyCode", TokenAddon.currencyCode);
                response.put("currencyId", Long.toUnsignedString(TokenAddon.currencyId));
                response.put("redemptionAccount", Long.toUnsignedString(TokenAddon.redemptionAccount));
                response.put("redemptionAccountRS", Convert.rsAccount(TokenAddon.redemptionAccount));
                response.put("confirmations", TokenAddon.confirmations);
                response.put("bitcoindAddress", TokenAddon.bitcoindAddress);
                response.put("bitcoindTxFee", TokenAddon.bitcoindTxFee.toPlainString());
                response.put("suspended", TokenListener.isSuspended());
                break;
            case "getTokens":
                int height;
                boolean includeExchanged;
                String heightString = Convert.emptyToNull(req.getParameter("height"));
                if (heightString == null) {
                    height = 0;
                } else {
                    try {
                        height = Integer.valueOf(heightString);
                    } catch (NumberFormatException exc) {
                        return incorrect("height", exc.getMessage());
                    }
                }
                String includeExchangedString = Convert.emptyToNull(req.getParameter("includeExchanged"));
                if (includeExchangedString == null) {
                    includeExchanged = false;
                } else {
                    includeExchanged = Boolean.valueOf(includeExchangedString);
                }
                List<TokenTransaction> tokenList = TokenDb.getTokens(height, includeExchanged);
                JSONArray tokenArray = new JSONArray();
                tokenList.forEach((token) -> {
                    JSONObject tokenObject = new JSONObject();
                    tokenObject.put("id", Long.toUnsignedString(token.getNxtTxId()));
                    tokenObject.put("sender", Long.toUnsignedString(token.getSenderId()));
                    tokenObject.put("senderRS", Convert.rsAccount(token.getSenderId()));
                    tokenObject.put("height", token.getHeight());
                    tokenObject.put("exchanged", token.isExchanged());
                    tokenObject.put("tokenAmount",
                            BigDecimal.valueOf(token.getTokenAmount(), TokenAddon.currencyDecimals).toPlainString());
                    tokenObject.put("bitcoinAmount",
                            BigDecimal.valueOf(token.getBitcoinAmount(), 8).toPlainString());
                    tokenObject.put("bitcoinAddress", token.getBitcoinAddress());
                    if (token.getBitcoinTxId() != null) {
                        tokenObject.put("bitcoinTxId", Convert.toHexString(token.getBitcoinTxId()));
                    }
                    tokenArray.add(tokenObject);
                });
                response.put("tokens", tokenArray);
                break;
            case "deleteToken":
                String idString = Convert.emptyToNull(req.getParameter("id"));
                if (idString == null) {
                    return missing("id");
                }
                long id = Long.parseUnsignedLong(idString);
                boolean deleted = TokenDb.deleteToken(id);
                response.put("deleted", deleted);
                break;
            case "suspendSend":
                TokenListener.suspendSend();
                response.put("suspended", TokenListener.isSuspended());
                break;
            case "resumeSend":
                TokenListener.resumeSend();
                response.put("suspended", TokenListener.isSuspended());
                break;
            case "getAddress":
                String accountString = Convert.emptyToNull(req.getParameter("account"));
                if (accountString == null) {
                    return missing("account");
                }
                long accountId = Convert.parseAccountId(accountString);
                String publicKeyString = Convert.emptyToNull(req.getParameter("publicKey"));
                byte[] publicKey;
                if (publicKeyString != null) {
                    publicKey = Convert.parseHexString(publicKeyString);
                    if (publicKey.length != 32) {
                        return incorrect("publicKey", "public key is not 32 bytes");
                    }
                } else {
                    publicKey = null;
                }
                BitcoinAccount account = TokenDb.getAccount(accountId);
                if (account == null) {
                    String address = BitcoinProcessor.getNewAddress(Convert.rsAccount(accountId));
                    if (address == null) {
                        return failure("Unable to get new Bitcoin address from server");
                    }
                    account = new BitcoinAccount(address, accountId, publicKey);
                    if (!TokenDb.storeAccount(account)) {
                        return failure("Unable to create Bitcoin account");
                    }
                }
                response.put("address", account.getBitcoinAddress());
                response.put("account", Long.toUnsignedString(accountId));
                response.put("accountRS", Convert.rsAccount(accountId));
                break;
            case "blockReceived":
                idString = Convert.emptyToNull(req.getParameter("id"));
                nxt.util.Logger.logDebugMessage("Block " + idString + " received");
                synchronized(bitcoinLock) {
                    if (!processingTransactions) {
                        processingTransactions = true;
                        TokenCurrency.processTransactions();
                        processingTransactions = false;
                    }
                }
                response.put("processed", true);
                break;
            case "transactionReceived":
                idString = Convert.emptyToNull(req.getParameter("id"));
                nxt.util.Logger.logDebugMessage("Transaction " + idString + " received");
                byte[] txid = Convert.parseHexString(idString);
                synchronized(bitcoinLock) {
                    if (TokenDb.getTransaction(txid) == null) {
                        BitcoinTransaction tx = BitcoinProcessor.getTransaction(txid);
                        if (tx != null) {
                            if (TokenDb.storeTransaction(tx)) {
                                Logger.logInfoMessage("Bitcoin transaction " + idString + " added to database");
                            } else {
                                Logger.logErrorMessage("Bitcoin transaction " + idString + " was not processed");
                            }
                        } else {
                            Logger.logDebugMessage("Bitcoin transaction " + idString + " does have a Nxt account");
                        }
                    } else {
                        Logger.logDebugMessage("Bitcoin transaction " + idString + " is already in the database");
                    }
                }
                response.put("processed", true);
                break;
            default:
                return unknown(function);
        }
        return response;
    }

    /**
     * Create response for a failure
     *
     * @param   message         Error message
     * @return                  Response
     */
    @SuppressWarnings("unchecked")
    private static JSONStreamAware failure(String message) {
        JSONObject response = new JSONObject();
        response.put("errorCode", 4);
        response.put("errorDescription", message);
        return JSON.prepare(response);
    }

    /**
     * Create response for a missing parameter
     *
     * @param   paramNames      Parameter names
     * @return                  Response
     */
    @SuppressWarnings("unchecked")
    private static JSONStreamAware missing(String... paramNames) {
        JSONObject response = new JSONObject();
        response.put("errorCode", 3);
        if (paramNames.length == 1) {
            response.put("errorDescription", "\"" + paramNames[0] + "\"" + " not specified");
        } else {
            response.put("errorDescription", "At least one of " + Arrays.toString(paramNames) + " must be specified");
        }
        return JSON.prepare(response);
    }

    /**
     * Create response for an incorrect parameter
     *
     * @param   paramName           Parameter name
     * @param   details             Error details
     * @return                      Response
     */
    @SuppressWarnings("unchecked")
    private static JSONStreamAware incorrect(String paramName, String details) {
        JSONObject response = new JSONObject();
        response.put("errorCode", 4);
        response.put("errorDescription", "Incorrect \"" + paramName + (details != null ? "\": " + details : "\""));
        return JSON.prepare(response);
    }

    /**
     * Create response for an unknown parameter
     *
     * @param   objectName          Parameter name
     * @return                      Response
     */
    @SuppressWarnings("unchecked")
    private static JSONStreamAware unknown(String objectName) {
        JSONObject response = new JSONObject();
        response.put("errorCode", 5);
        response.put("errorDescription", "Unknown " + objectName);
        return JSON.prepare(response);
    }

    /**
     * Require POST since we use the administrator password
     *
     * @return                  TRUE if POST is required
     */
    @Override
    protected boolean requirePost() {
        return true;
    }

    /**
     * Require the administrator password
     *
     * @return                  TRUE if adminPassword is required
     */
    @Override
    protected boolean requirePassword() {
        return true;
    }

    /**
     * We don't use the required block parameters
     *
     * @return                  TRUE if required block parameters are needed
     */
    @Override
    protected boolean allowRequiredBlockParameters() {
        return false;
    }

    /**
     * We require the full client
     *
     * @return                  TRUE if full client is required
     */
    @Override
    protected boolean requireFullClient() {
        return true;
    }
}
