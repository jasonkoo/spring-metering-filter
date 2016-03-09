package com.ge.predix.metering.filter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.mockito.Matchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpEntity;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.client.AsyncRestTemplate;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import com.ge.predix.metering.customer.Customer;
import com.ge.predix.metering.data.entity.MeteredResource;
import com.ge.predix.metering.nurego.AsyncNuregoClient;

@Test(dependsOnGroups="asyncNuregoSingleThreadedTest")
public class AsyncNuregoClientMultiThreadTest {

    private static final String SUBSCRIPTION_1 = "subscription_123";
    private static final String SUBSCRIPTION_2 = "subscription_456";

    final List<Record> records = Collections.synchronizedList(new ArrayList<Record>());
    private AsyncNuregoClient asyncNuregoClient = null;

    @SuppressWarnings("unchecked")
    @BeforeClass
    public void setup() {
        AsyncRestTemplate asyncRestTemplate = Mockito.mock(AsyncRestTemplate.class);
        Mockito.when(asyncRestTemplate.postForEntity(Matchers.anyString(), Matchers.any(HttpEntity.class),
                Matchers.any(Class.class))).thenAnswer(new Answer<ListenableFuture<?>>() {
                    @Override
                    public ListenableFuture<?> answer(final InvocationOnMock invocation) throws Throwable {
                        Object[] args = invocation.getArguments();
                        String url = (String) args[0];
                        String subscriptionId = url.split("/")[5];
                        System.out.println("Thread id: " + Thread.currentThread().getId() + " subscription id: "
                                + subscriptionId + " from url: " + url);
                        HttpEntity<?> request = (HttpEntity<?>) args[1];
                        Map<String, Object> map = (Map<String, Object>) request.getBody();
                        String featureId = (String) map.get("feature_id");
                        Integer amount = (Integer) map.get("amount");
                        AsyncNuregoClientMultiThreadTest.this.records
                                .add(new Record(subscriptionId, featureId, amount));
                        return null;
                    }
                });
        this.asyncNuregoClient = new AsyncNuregoClient("https://hello", "hello", 3, 3);
        this.asyncNuregoClient.setAsyncRestTemplate(asyncRestTemplate);
    }

    
    @Test(threadPoolSize=5, invocationCount=5, dataProvider="meterDataProvider")
    public void testUpdateAmount(Customer customer, MeteredResource meter, int amount) {
          this.asyncNuregoClient.updateAmount(customer, meter, amount);
    }
    
    /**
     * This test asserts the cumulative count of updates executed in {@link #testUpdateAmount()} with mutliple 
     * threads
     */
    @Test(dependsOnMethods="testUpdateAmount")
    public void testAssertMeterData() {
        //flush any updates in cache
        this.asyncNuregoClient.flushMeterUpdates();
        
        Map<String, Integer> recordsReceivedByProvider = new HashMap<>();
        for (Record record : this.records) {
            String key = record.getSubscriptionId() + record.getFeatureId();
            Integer currentAmount = recordsReceivedByProvider.get(key);
            if (currentAmount == null) {
                recordsReceivedByProvider.put(key, record.getAmount());
            } else {
                recordsReceivedByProvider.put(key, currentAmount + record.getAmount());
            }
        }
        
        Assert.assertEquals(recordsReceivedByProvider.size(), 4);
        Assert.assertEquals(recordsReceivedByProvider.get(SUBSCRIPTION_1+FEATURE_1), new Integer(5));
        Assert.assertEquals(recordsReceivedByProvider.get(SUBSCRIPTION_1+FEATURE_2), new Integer(5));
        Assert.assertEquals(recordsReceivedByProvider.get(SUBSCRIPTION_2+FEATURE_1), new Integer(5));
        Assert.assertEquals(recordsReceivedByProvider.get(SUBSCRIPTION_2+FEATURE_2), new Integer(5));

    }

    
    private final static String FEATURE_1 = "f1";
    private final static String FEATURE_2 = "f2";
    
    @DataProvider(parallel = true)
    public Object[][] meterDataProvider() {
        Customer customer_1 = new Customer(null, SUBSCRIPTION_1);
        Customer customer_2 = new Customer(null, SUBSCRIPTION_2);
        return new Object[][] {
                new Object[] { customer_1, new MeteredResource("POST", "/users", 201, FEATURE_1),
                        1 },
                new Object[] { customer_1, new MeteredResource("POST", "/users", 201, FEATURE_2),
                        1 },
                new Object[] { customer_2, new MeteredResource("POST", "/users", 201, FEATURE_1),
                        1 },
                new Object[] { customer_2, new MeteredResource("POST", "/users", 201, FEATURE_2),
                        1 }
                 };
    }


    static class Record {
        private final String subscriptionId;
        private final String featureId;
        private final Integer amount;

        public Record(final String subscriptionId, final String featureId, final Integer amount) {
            super();
            this.subscriptionId = subscriptionId;
            this.featureId = featureId;
            this.amount = amount;
        }

        public String getSubscriptionId() {
            return this.subscriptionId;
        }

        public String getFeatureId() {
            return this.featureId;
        }

        public Integer getAmount() {
            return this.amount;
        }

    }
}