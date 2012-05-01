/* 
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.billing.beatrix.integration;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.IDBI;
import org.skife.jdbi.v2.TransactionCallback;
import org.skife.jdbi.v2.TransactionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;

import com.google.inject.Inject;
import com.ning.billing.account.api.AccountData;
import com.ning.billing.account.api.AccountService;
import com.ning.billing.account.api.AccountUserApi;
import com.ning.billing.beatrix.lifecycle.Lifecycle;
import com.ning.billing.catalog.api.Currency;
import com.ning.billing.dbi.MysqlTestingHelper;
import com.ning.billing.entitlement.api.EntitlementService;
import com.ning.billing.entitlement.api.timeline.EntitlementTimelineApi;
import com.ning.billing.entitlement.api.user.EntitlementUserApi;
import com.ning.billing.entitlement.api.user.SubscriptionData;
import com.ning.billing.invoice.api.Invoice;
import com.ning.billing.invoice.api.InvoiceItem;
import com.ning.billing.invoice.api.InvoiceService;
import com.ning.billing.invoice.api.InvoiceUserApi;
import com.ning.billing.invoice.model.InvoicingConfiguration;
import com.ning.billing.util.bus.BusService;
import com.ning.billing.util.callcontext.CallContext;
import com.ning.billing.util.callcontext.CallOrigin;
import com.ning.billing.util.callcontext.DefaultCallContextFactory;
import com.ning.billing.util.callcontext.UserType;
import com.ning.billing.util.clock.ClockMock;

public class TestIntegrationBase implements TestFailure {

    protected static final int NUMBER_OF_DECIMALS = InvoicingConfiguration.getNumberOfDecimals();
    protected static final int ROUNDING_METHOD = InvoicingConfiguration.getRoundingMode();

    protected static final BigDecimal ONE = new BigDecimal("1.0000").setScale(NUMBER_OF_DECIMALS);
    protected static final BigDecimal TWENTY_NINE = new BigDecimal("29.0000").setScale(NUMBER_OF_DECIMALS);
    protected static final BigDecimal THIRTY = new BigDecimal("30.0000").setScale(NUMBER_OF_DECIMALS);
    protected static final BigDecimal THIRTY_ONE = new BigDecimal("31.0000").setScale(NUMBER_OF_DECIMALS);

    protected static final Logger log = LoggerFactory.getLogger(TestIntegration.class);
    protected static long AT_LEAST_ONE_MONTH_MS =  31L * 24L * 3600L * 1000L;

    protected static final long DELAY = 5000;

    @Inject
    protected IDBI dbi;

    @Inject
    protected ClockMock clock;
    
    protected CallContext context;

    @Inject
    protected Lifecycle lifecycle;

    @Inject
    protected BusService busService;

    @Inject
    protected EntitlementService entitlementService;

    @Inject
    protected InvoiceService invoiceService;

    @Inject
    protected AccountService accountService;

    @Inject
    protected MysqlTestingHelper helper;
    @Inject
    protected EntitlementUserApi entitlementUserApi;

    @Inject
    protected EntitlementTimelineApi repairApi;
    
    @Inject
    protected InvoiceUserApi invoiceUserApi;

    @Inject
    protected AccountUserApi accountUserApi;

    protected TestBusHandler busHandler;

    
    private boolean currentTestStatusSuccess;
    private String currentTestFailedMsg;
    
    @Override
    public void failed(String msg) {
        currentTestStatusSuccess = false;
        currentTestFailedMsg = msg;
    }

    @Override
    public void reset() {
        currentTestStatusSuccess = true;
        currentTestFailedMsg = null;
    }

    protected void assertFailureFromBusHandler() {
        if (!currentTestStatusSuccess) {
            log.error(currentTestFailedMsg);
            fail();
        }
    }

    protected void setupMySQL() throws IOException
    {
        final String accountDdl = IOUtils.toString(TestIntegration.class.getResourceAsStream("/com/ning/billing/account/ddl.sql"));
        final String entitlementDdl = IOUtils.toString(TestIntegration.class.getResourceAsStream("/com/ning/billing/entitlement/ddl.sql"));
        final String invoiceDdl = IOUtils.toString(TestIntegration.class.getResourceAsStream("/com/ning/billing/invoice/ddl.sql"));
        final String paymentDdl = IOUtils.toString(TestIntegration.class.getResourceAsStream("/com/ning/billing/payment/ddl.sql"));
        final String utilDdl = IOUtils.toString(TestIntegration.class.getResourceAsStream("/com/ning/billing/util/ddl.sql"));

        helper.startMysql();

        helper.initDb(accountDdl);
        helper.initDb(entitlementDdl);
        helper.initDb(invoiceDdl);
        helper.initDb(paymentDdl);
        helper.initDb(utilDdl);
    }

    @BeforeClass(groups = "slow")
    public void setup() throws Exception{

        setupMySQL();
        
        cleanupData();
        
        context = new DefaultCallContextFactory(clock).createCallContext("Integration Test", CallOrigin.TEST, UserType.TEST);

        /**
         * Initialize lifecyle for subset of services
         */
        busHandler = new TestBusHandler(this);
        lifecycle.fireStartupSequencePriorEventRegistration();
        busService.getBus().register(busHandler);
        lifecycle.fireStartupSequencePostEventRegistration();
    }

    @AfterClass(groups = "slow")
    public void tearDown() throws Exception {
        lifecycle.fireShutdownSequencePriorEventUnRegistration();
        busService.getBus().unregister(busHandler);
        lifecycle.fireShutdownSequencePostEventUnRegistration();
        helper.stopMysql();
    }


    @BeforeMethod(groups = "slow")
    public void setupTest() {

        log.warn("\n");
        log.warn("RESET TEST FRAMEWORK\n\n");
        cleanupData();
        busHandler.reset();
        clock.resetDeltaFromReality();
        reset();
    }

    @AfterMethod(groups = "slow")
    public void cleanupTest() {
        log.warn("DONE WITH TEST\n");
    }
    
    protected void cleanupData() {
        dbi.inTransaction(new TransactionCallback<Void>() {
            @Override
            public Void inTransaction(Handle h, TransactionStatus status)
                    throws Exception {
                h.execute("truncate table accounts");
                h.execute("truncate table entitlement_events");
                h.execute("truncate table subscriptions");
                h.execute("truncate table bundles");
                h.execute("truncate table notifications");
                h.execute("truncate table claimed_notifications");
                h.execute("truncate table invoices");
                h.execute("truncate table fixed_invoice_items");
                h.execute("truncate table recurring_invoice_items");
                h.execute("truncate table tag_definitions");
                h.execute("truncate table tags");
                h.execute("truncate table custom_fields");
                h.execute("truncate table invoice_payments");
                h.execute("truncate table payment_attempts");
                h.execute("truncate table payments");
                return null;
            }
        });
    }

    protected void verifyTestResult(UUID accountId, UUID subscriptionId,
                                  DateTime startDate, DateTime endDate,
                                  BigDecimal amount, DateTime chargeThroughDate,
                                  int totalInvoiceItemCount) {
        SubscriptionData subscription = (SubscriptionData) entitlementUserApi.getSubscriptionFromId(subscriptionId);

        List<Invoice> invoices = invoiceUserApi.getInvoicesByAccount(accountId);
        List<InvoiceItem> invoiceItems = new ArrayList<InvoiceItem>();
        for (Invoice invoice : invoices) {
            invoiceItems.addAll(invoice.getInvoiceItems());
        }
        assertEquals(invoiceItems.size(), totalInvoiceItemCount);

        boolean wasFound = false;

        for (InvoiceItem item : invoiceItems) {
            if (item.getStartDate().compareTo(startDate) == 0) {
                if (item.getEndDate().compareTo(endDate) == 0) {
                    if (item.getAmount().compareTo(amount) == 0) {
                        wasFound = true;
                        break;
                    }
                }
            }
        }

        if (!wasFound) {
            fail();
        }

        DateTime ctd = subscription.getChargedThroughDate();
        assertNotNull(ctd);
        log.info("Checking CTD: " + ctd.toString() + "; clock is " + clock.getUTCNow().toString());
        assertTrue(clock.getUTCNow().isBefore(ctd));
        assertTrue(ctd.compareTo(chargeThroughDate) == 0);
    }
    
    
    protected AccountData getAccountData(final int billingDay) {

        final String someRandomKey = RandomStringUtils.randomAlphanumeric(10);
        return new AccountData() {
            @Override
            public String getName() {
                return "firstName lastName";
            }
            @Override
            public int getFirstNameLength() {
                return "firstName".length();
            }
            @Override
            public String getEmail() {
                return  someRandomKey + "@laposte.fr";
            }
            @Override
            public String getPhone() {
                return "4152876341";
            }
            @Override
            public String getExternalKey() {
                return someRandomKey;
            }
            @Override
            public int getBillCycleDay() {
                return billingDay;
            }
            @Override
            public Currency getCurrency() {
                return Currency.USD;
            }
            @Override
            public String getPaymentProviderName() {
                return MockModule.PLUGIN_NAME;
            }

            @Override
            public DateTimeZone getTimeZone() {
                return null;
            }

            @Override
            public String getLocale() {
                return null;
            }

            @Override
            public String getAddress1() {
                return null;
            }

            @Override
            public String getAddress2() {
                return null;
            }

            @Override
            public String getCompanyName() {
                return null;
            }

            @Override
            public String getCity() {
                return null;
            }

            @Override
            public String getStateOrProvince() {
                return null;
            }

            @Override
            public String getPostalCode() {
                return null;
            }

            @Override
            public String getCountry() {
                return null;
            }
        };
    }
}