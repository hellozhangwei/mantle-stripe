package com.stripe

import com.stripe.Stripe
import com.stripe.model.Charge
import com.stripe.model.Customer
import com.stripe.model.PaymentMethod
import com.stripe.model.PaymentMethodCollection
import com.stripe.model.Refund
import com.stripe.exception.StripeException
import com.stripe.exception.CardException
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.CustomerCreateParams
import com.stripe.param.PaymentMethodListParams
import com.stripe.param.RefundCreateParams;
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

class TransactionServices {
    static Map sendAuthorizeRequest (ExecutionContext ec) {
        def secretKey = ec.context.secretKey
        def creditCardInfo = ec.context.creditCardInfo
        def transactionInfo = ec.context.transactionInfo
        transactionInfo.amount = transactionInfo.amount.toInteger() * 100 // dollars to cents needed because stripe records USD amounts by the smallest division (cents)
        Stripe.apiKey = secretKey
        def responseMap = [:]

        if (creditCardInfo.paymentRefNum) {
            PaymentIntent intent;
            try {
                PaymentIntentCreateParams createParams = PaymentIntentCreateParams.builder()
                        .setAmount(transactionInfo.amount)
                        .setCurrency(transactionInfo.currency)
                        .setConfirm(true)
                        .addPaymentMethodType("card")
                        .setPaymentMethod(creditCardInfo.paymentRefNum)
                        .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)// TODO Use transactionInfo.capture to define capture
                        .build();
                        // TODO Understand and use .setConfirm(true)
                        // TODO Understand and use .setConfirmationMethod(PaymentIntentCreateParams.ConfirmationMethod.MANUAL)
                        // TODO Use it for separate capturing
                intent = PaymentIntent.create(createParams);
                // TODO Find a better way using intent object
                responseMap.amount = transactionInfo.amount;
                // TODO Handle case if authorisation require multi step authentication (like 3D Secure authentication)
                // intent = PaymentIntent.retrieve(confirmRequest.getPaymentIntentId());
                // intent = intent.confirm();

                // Note that if your API version is before 2019-02-11, 'requires_action'
                // appears as 'requires_source_action'.
                if (intent.getStatus().equals("requires_action")
                        && intent.getNextAction().getType().equals("use_stripe_sdk")) {
                    responseMap.errorInfo = ['request': 'authorise', 'responseCode':'1', 'status': intent.getStatus() ,'requires_action': true, 'payment_intent_client_secret': intent.getClientSecret(), 'payment_intent_id': intent.getId()]
                } else if (intent.getStatus().equals("requires_confirmation")) {
                    responseMap.errorInfo = ['request': 'authorise', 'responseCode':'1', 'status': intent.getStatus(),'requires_confirmation': true, 'payment_intent_client_secret': intent.getClientSecret(), 'payment_intent_id': intent.getId()]
                } else if (intent.getStatus().equals("succeeded")) {
                    responseMap.errorInfo = ['request': 'authorise', 'responseCode':'1', 'status': intent.getStatus(), 'payment_intent_client_secret': intent.getClientSecret(), 'payment_intent_id': intent.getId()] // '1' = success
                }  else if (intent.getStatus().equals("requires_capture")) {
                    // If you’re separately authorizing and capturing funds, your PaymentIntent may also move to requires_capture.
                    // https://stripe.com/docs/payments/intents
                    // https://stripe.com/docs/payments/capture-later
                    responseMap.errorInfo = ['request': 'authorise', 'responseCode':'1', 'status': intent.getStatus(), 'payment_intent_client_secret': intent.getClientSecret(), 'payment_intent_id': intent.getId()] // '1' = success
                } else {
                    // invalid status
                    responseMap.errorInfo = ['request': 'authorise', 'responseCode':'3', 'status': intent.getStatus(),'reasonCode':'','reasonMessage': 'Invalid status']
                }
            } catch (Exception e) {
                responseMap.errorInfo = ['request': 'authorise', 'responseCode':'3','reasonCode':'','reasonMessage':e.getMessage(),'exception':e]
            }
        } else {
            def tokenResponse = TokenServices.generateToken(ec).responseMap
            if (!tokenResponse.token) return ['responseMap':tokenResponse] // passing the error info back up to caller

            transactionInfo.source = tokenResponse.token.id

            try {
                def charge = Charge.create(transactionInfo)

                responseMap.charge = charge
                responseMap.errorInfo = ['responseCode':'1'] // '1' = success

            } catch (CardException e) {
                responseMap.errorInfo = ['responseCode':'2','reasonCode':e.getCode(),'reasonMessage':e.getMessage(),'exception':e]
            } catch (StripeException e) {
                responseMap.errorInfo = ['responseCode':'3','reasonCode':e.getCode(),'reasonMessage':e.getMessage(),'exception':e]
            } catch (Exception e) {
                responseMap.errorInfo = ['responseCode':'3','reasonCode':'','reasonMessage':e.getMessage(),'exception':e]
            }
        }

        return ['responseMap':responseMap]
    }

    static Map sendAuthorizeRequestUsingSetUpFuturePayments(ExecutionContext ec) {
        def secretKey = ec.context.secretKey
        def creditCardInfo = ec.context.creditCardInfo
        def transactionInfo = ec.context.transactionInfo
        println "===============-transactionInfo-=====${transactionInfo}======================"
        transactionInfo.amount = transactionInfo.amount.toInteger() * 100 // dollars to cents needed because stripe records USD amounts by the smallest division (cents)
        Stripe.apiKey = secretKey
        def responseMap = [:]
        println "===============transactionInfo=====${transactionInfo}======================"

        if (creditCardInfo.paymentRefNum) {
            PaymentIntent intent;
            try {
                // TODO Find a better way using intent object
                responseMap.amount = transactionInfo.amount;


                // Check if the party is an customer with existing Gateway cim id
                EntityValue fromParty = ec.entity.find("mantle.party.Party")
                        .condition("partyId", creditCardInfo.payment.fromPartyId).one()

                String customerId = fromParty.gatewayCimId;
                println "==============customerId==${customerId}======================"

                if (customerId == null) {
                    CustomerCreateParams params = CustomerCreateParams.builder()
                            .build();
                    Customer customer = Customer.create(params);
                    customerId = customer.getId();
                    PaymentMethodListParams paymentMethodListParams =
                            PaymentMethodListParams.builder()
                                    .setCustomer(customerId)
                                    .setType(PaymentMethodListParams.Type.CARD)
                                    .build();
                    PaymentMethodCollection paymentMethods = PaymentMethod.list(paymentMethodListParams);
                    // Set new customer Id as gatewayCimId for future reference
                    fromParty.gatewayCimId = customerId;
                    fromParty.update()
                    println "==============paymentMethods==${paymentMethods}======================"
                    println "==============fromParty==${fromParty}======================"
                }



                println "==============from party id==${creditCardInfo.payment.fromPartyId}======================"
                PaymentIntentCreateParams createParams = PaymentIntentCreateParams.builder()
                        .setAmount(transactionInfo.amount)
                        .setCurrency(transactionInfo.currency)
                        .setConfirm(true)
                        .addPaymentMethodType("card")
                        .setPaymentMethod(creditCardInfo.paymentRefNum)
                        .setCustomer(customerId)
                        .setOffSession(true)
                        .putMetadata("orderId", creditCardInfo.payment.orderId)
                        .putMetadata("orderPartSeqId", creditCardInfo.payment.orderPartSeqId)
                        .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)// TODO Use transactionInfo.capture to define capture
                        .build();
                println "===============createParams=====${createParams}======================"
                // TODO Understand and use .setConfirmationMethod(PaymentIntentCreateParams.ConfirmationMethod.MANUAL)
                // TODO Use it for separate capturing
                intent = PaymentIntent.create(createParams);
                // TODO Handle case if authorisation require multi step authentication (like 3D Secure authentication)
                // intent = PaymentIntent.retrieve(confirmRequest.getPaymentIntentId());
                // intent = intent.confirm();

                // Note that if your API version is before 2019-02-11, 'requires_action'
                // appears as 'requires_source_action'.
                if (intent.getStatus().equals("requires_action")
                        && intent.getNextAction().getType().equals("use_stripe_sdk")) {
                    responseMap.errorInfo = ['request': 'authorise', 'responseCode':'1', 'status': intent.getStatus() ,'requires_action': true, 'payment_intent_client_secret': intent.getClientSecret(), 'payment_intent_id': intent.getId()]
                } else if (intent.getStatus().equals("requires_confirmation")) {
                    responseMap.errorInfo = ['request': 'authorise', 'responseCode':'1', 'status': intent.getStatus(),'requires_confirmation': true, 'payment_intent_client_secret': intent.getClientSecret(), 'payment_intent_id': intent.getId()]
                } else if (intent.getStatus().equals("succeeded")) {
                    responseMap.errorInfo = ['request': 'authorise', 'responseCode':'1', 'status': intent.getStatus(), 'payment_intent_client_secret': intent.getClientSecret(), 'payment_intent_id': intent.getId()] // '1' = success
                }  else if (intent.getStatus().equals("requires_capture")) {
                    // If you’re separately authorizing and capturing funds, your PaymentIntent may also move to requires_capture.
                    // https://stripe.com/docs/payments/intents
                    // https://stripe.com/docs/payments/capture-later
                    responseMap.errorInfo = ['request': 'authorise', 'responseCode':'1', 'status': intent.getStatus(), 'payment_intent_client_secret': intent.getClientSecret(), 'payment_intent_id': intent.getId()] // '1' = success
                } else {
                    // invalid status
                    responseMap.errorInfo = ['request': 'authorise', 'responseCode':'3', 'status': intent.getStatus(),'reasonCode':'','reasonMessage': 'Invalid status']
                }
            } catch (Exception e) {
                // TODO Handle error and decline code (authentication_required)
                // https://stripe.com/docs/error-codes
                // https://support.stripe.com/questions/authenticated-payment-declined-with-an-authentication-required-decline-code
                responseMap.errorInfo = ['request': 'authorise', 'responseCode':'3','reasonCode':'','reasonMessage':e.getMessage(),'exception':e]
            }
        } else {
            def tokenResponse = TokenServices.generateToken(ec).responseMap
            if (!tokenResponse.token) return ['responseMap':tokenResponse] // passing the error info back up to caller

            transactionInfo.source = tokenResponse.token.id

            try {
                def charge = Charge.create(transactionInfo)

                responseMap.charge = charge
                responseMap.errorInfo = ['responseCode':'1'] // '1' = success

            } catch (CardException e) {
                responseMap.errorInfo = ['responseCode':'2','reasonCode':e.getCode(),'reasonMessage':e.getMessage(),'exception':e]
            } catch (StripeException e) {
                responseMap.errorInfo = ['responseCode':'3','reasonCode':e.getCode(),'reasonMessage':e.getMessage(),'exception':e]
            } catch (Exception e) {
                responseMap.errorInfo = ['responseCode':'3','reasonCode':'','reasonMessage':e.getMessage(),'exception':e]
            }
        }

        return ['responseMap':responseMap]
    }

    static Map sendCaptureRequest (ExecutionContext ec) {
        def secretKey = ec.context.secretKey
        def chargeId = ec.context.chargeId
        def amount = ec.context.amount
        def referenceNum = ec.context.referenceNum;
        def intentSecret = ec.context.intentSecret;
        def intentId = ec.context.intentId;

        amount = amount.toInteger() * 100 // dollars to cents needed because stripe records USD amounts by the smallest division (cents)

        Stripe.apiKey = secretKey

        def responseMap = [:]

        if(referenceNum) {
            try {
                PaymentIntent intent = PaymentIntent.retrieve(intentId);

                PaymentIntentCaptureParams params =
                        PaymentIntentCaptureParams.builder()
                                .setAmountToCapture(amount)
                                .build();

                intent = intent.capture(params);
                responseMap.amount = amount;

                responseMap.errorInfo = ['request': 'capture' ,'responseCode':'1', 'status': intent.getStatus(),] // '1' = success

            } catch (StripeException e) {
                responseMap.errorInfo = ['request': 'capture', 'responseCode':'3','reasonCode':e.getCode(),'reasonMessage':e.getMessage(),'exception':e]
            } catch (Exception e) {
                responseMap.errorInfo = ['request': 'capture', 'responseCode':'3','reasonCode':'','reasonMessage':e.getMessage(),'exception':e]
            }

        } else {
            try {
                def charge = Charge.retrieve(chargeId)
                if (amount != null) charge.capture(['amount':amount])
                else charge.capture()

                responseMap.charge = charge
                responseMap.errorInfo = ['responseCode':'1'] // '1' = success

            } catch (StripeException e) {
                responseMap.errorInfo = ['responseCode':'3','reasonCode':e.getCode(),'reasonMessage':e.getMessage(),'exception':e]
            } catch (Exception e) {
                responseMap.errorInfo = ['responseCode':'3','reasonCode':'','reasonMessage':e.getMessage(),'exception':e]
            }
        }
        return ['responseMap':responseMap]
    }

    static Map sendRefundRequest (ExecutionContext ec) {
        def secretKey = ec.context.secretKey
        def chargeId = ec.context.chargeId
        def amount = ec.context.amount
        def referenceNum = ec.context.referenceNum;
        def intentSecret = ec.context.intentSecret;
        def intentId = ec.context.intentId;

        println "================amount====================" + amount;
        amount = amount.toInteger() * 100 // dollars to cents needed because stripe records USD amounts by the smallest division (cents)

        Stripe.apiKey = secretKey

        println "================Stripe.apiVersion====================" + Stripe.apiVersion;

        def responseMap = [:]

        if(referenceNum) {
            try {
                Refund refund = Refund.create(RefundCreateParams.builder()
                        .setAmount(amount)
                        .setPaymentIntent(intentId)
                        .build());

                responseMap.amount = amount;

                responseMap.errorInfo = ['request': 'refund' ,'responseCode':'1', 'status': refund.getStatus(),] // '1' = success

            } catch (StripeException e) {
                responseMap.errorInfo = ['request': 'refund', 'responseCode':'3','reasonCode':e.getCode(),'reasonMessage':e.getMessage(),'exception':e]
            } catch (Exception e) {
                responseMap.errorInfo = ['request': 'refund', 'responseCode':'3','reasonCode':'','reasonMessage':e.getMessage(),'exception':e]
            }

        } else {
            try {
                def charge = Charge.retrieve(chargeId)
                if (amount != null) charge.capture(['amount':amount])
                else charge.capture()

                responseMap.charge = charge
                responseMap.errorInfo = ['responseCode':'1'] // '1' = success

            } catch (StripeException e) {
                responseMap.errorInfo = ['responseCode':'3','reasonCode':e.getCode(),'reasonMessage':e.getMessage(),'exception':e]
            } catch (Exception e) {
                responseMap.errorInfo = ['responseCode':'3','reasonCode':'','reasonMessage':e.getMessage(),'exception':e]
            }
        }
        return ['responseMap':responseMap]
    }
}