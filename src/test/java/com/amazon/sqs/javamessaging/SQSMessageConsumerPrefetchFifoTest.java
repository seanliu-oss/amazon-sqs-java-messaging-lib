/*
 * Copyright 2010-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.amazon.sqs.javamessaging;

import com.amazon.sqs.javamessaging.AmazonSQSMessagingClientWrapper;
import com.amazon.sqs.javamessaging.SQSConnection;
import com.amazon.sqs.javamessaging.SQSMessageConsumerPrefetch;
import com.amazon.sqs.javamessaging.SQSQueueDestination;
import com.amazon.sqs.javamessaging.SQSSessionCallbackScheduler;
import com.amazon.sqs.javamessaging.acknowledge.Acknowledger;
import com.amazon.sqs.javamessaging.acknowledge.NegativeAcknowledger;
import com.amazon.sqs.javamessaging.message.SQSBytesMessage;
import com.amazon.sqs.javamessaging.message.SQSMessage;
import com.amazon.sqs.javamessaging.message.SQSObjectMessage;
import com.amazon.sqs.javamessaging.message.SQSTextMessage;
import com.amazon.sqs.javamessaging.util.ExponentialBackoffStrategy;

import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.Message.Builder;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.utils.BinaryUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jms.JMSException;
import javax.jms.ObjectMessage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.ArgumentMatcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test the SQSMessageConsumerPrefetchTest class
 */
@RunWith(Parameterized.class)
public class SQSMessageConsumerPrefetchFifoTest {

    private static final String NAMESPACE = "123456789012";
    private static final String QUEUE_NAME = "QueueName.fifo";
    private static final  String QUEUE_URL = NAMESPACE + "/" + QUEUE_NAME;

    private Acknowledger acknowledger;
    private NegativeAcknowledger negativeAcknowledger;
    private SQSSessionCallbackScheduler sqsSessionRunnable;
    private SQSMessageConsumerPrefetch consumerPrefetch;
    private ExponentialBackoffStrategy backoffStrategy;

    private AmazonSQSMessagingClientWrapper amazonSQSClient;

    @Parameters
    public static List<Object[]> getParameters() {
        return Arrays.asList(new Object[][] { {0}, {1}, {5}, {10}, {15} });
    }
   
    private final int numberOfMessagesToPrefetch;
    
    public SQSMessageConsumerPrefetchFifoTest(int numberOfMessagesToPrefetch) {
        this.numberOfMessagesToPrefetch = numberOfMessagesToPrefetch;
    }
    
    @Before
    public void setup() {

        amazonSQSClient = mock(AmazonSQSMessagingClientWrapper.class);

        SQSConnection parentSQSConnection = mock(SQSConnection.class);
        when(parentSQSConnection.getWrappedAmazonSQSClient()).thenReturn(amazonSQSClient);

        sqsSessionRunnable = mock(SQSSessionCallbackScheduler.class);

        acknowledger = mock(Acknowledger.class);

        negativeAcknowledger = mock(NegativeAcknowledger.class);

        backoffStrategy = mock(ExponentialBackoffStrategy.class);

        SQSQueueDestination sqsDestination = new SQSQueueDestination(QUEUE_NAME, QUEUE_URL);

        consumerPrefetch =
                spy(new SQSMessageConsumerPrefetch(sqsSessionRunnable, acknowledger, negativeAcknowledger,
                        sqsDestination, amazonSQSClient, numberOfMessagesToPrefetch));

        consumerPrefetch.backoffStrategy = backoffStrategy;
    }

    /**
     * Test one full prefetch operation works as expected
     */
    @Test
    public void testOneFullPrefetch() throws InterruptedException, JMSException {

        /*
         * Set up consumer prefetch and mocks
         */

        final int numMessages = numberOfMessagesToPrefetch > 0 ? numberOfMessagesToPrefetch : 1;
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < numMessages; i++) {
            messages.add(createValidFifoMessage(i, "G" + i).build());
        }

        // First start the consumer prefetch
        consumerPrefetch.start();

        // Mock SQS call for receive message and return messages
        final int receiveMessageLimit = Math.min(10, numMessages);
        when(amazonSQSClient.receiveMessage(argThat(new ArgumentMatcher<ReceiveMessageRequest>() {
                    @Override
                    public boolean matches(Object argument) {
                        if (!(argument instanceof ReceiveMessageRequest))
                            return false;
                        ReceiveMessageRequest other = (ReceiveMessageRequest)argument;
                        
                        return other.queueUrl().equals(QUEUE_URL)
                                && other.maxNumberOfMessages() == receiveMessageLimit
                                && other.messageAttributeNames().size() == 1
                                && other.messageAttributeNames().get(0).equals(SQSMessageConsumerPrefetch.ALL)
                                && other.waitTimeSeconds() == SQSMessageConsumerPrefetch.WAIT_TIME_SECONDS
                                && other.receiveRequestAttemptId() != null
                                && other.receiveRequestAttemptId().length() > 0;
                    }            
                })))
                .thenReturn(ReceiveMessageResponse.builder().messages(messages).build());

        // Mock isClosed and exit after a single prefetch loop
        when(consumerPrefetch.isClosed())
                .thenReturn(false)
                .thenReturn(false)
                .thenReturn(false)
                .thenReturn(true);

        /*
         * Request a message (only relevant when prefetching is off).
         */
        consumerPrefetch.requestMessage();
        
        /*
         * Run the prefetch
         */
        consumerPrefetch.run();

        /*
         * Verify the results
         */

        // Ensure Consumer was started
        verify(consumerPrefetch).waitForStart();

        // Ensure Consumer Prefetch backlog is not full
        verify(consumerPrefetch).waitForPrefetch();

        // Ensure no message was nack
        verify(negativeAcknowledger).action(QUEUE_URL, new ArrayList<String>());

        // Ensure retries attempt was not increased
        assertEquals(0, consumerPrefetch.retriesAttempted);

        // Ensure message queue was filled with expected messages
        assertEquals(numMessages, consumerPrefetch.messageQueue.size());
        int index = 0;
        for (SQSMessageConsumerPrefetch.MessageManager messageManager : consumerPrefetch.messageQueue) {
            Message mockedMessage = messages.get(index);
            SQSMessage sqsMessage = (SQSMessage)messageManager.getMessage();
            assertEquals(
                    "Receipt handle is the same",
                    mockedMessage.receiptHandle(), sqsMessage.getReceiptHandle());
            assertEquals(
                    "Group id is the same",
                    mockedMessage
                            .attributes()
                            .get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.MESSAGE_GROUP_ID)),
                    sqsMessage.getStringProperty(SQSMessagingClientConstants.JMSX_GROUP_ID));
            assertEquals(
                    "Sequence number is the same",
                    mockedMessage
                            .attributes()
                            .get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.SEQUENCE_NUMBER)),
                    sqsMessage.getStringProperty(SQSMessagingClientConstants.JMS_SQS_SEQUENCE_NUMBER));
            assertEquals(
                    "Deduplication id is the same",
                    mockedMessage
                            .attributes()
                            .get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.MESSAGE_DEDUPLICATION_ID)),
                    sqsMessage.getStringProperty(SQSMessagingClientConstants.JMS_SQS_DEDUPLICATION_ID));
            
            index++;
        }
    }

    /**
     * Test ConvertToJMSMessage when message type is not set in the message attribute
     */
    @Test
    public void testConvertToJMSMessageNoTypeAttribute() throws JMSException {

        /*
         * Set up consumer prefetch and mocks
         */
    	// Return message attribute with no message type attribute
    	Message message = createValidFifoMessage(1, "G").body("MessageBody").build();

        /*
         * Convert the SQS message to JMS Message
         */
        javax.jms.Message jmsMessage = consumerPrefetch.convertToJMSMessage(message);

        /*
         * Verify results
         */
        assertTrue(jmsMessage instanceof SQSTextMessage);
        assertEquals(((SQSTextMessage) jmsMessage).getText(), "MessageBody");
        assertEquals(message.attributes().get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.MESSAGE_DEDUPLICATION_ID)), jmsMessage.getStringProperty(SQSMessagingClientConstants.JMS_SQS_DEDUPLICATION_ID));
        assertEquals(message.attributes().get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.SEQUENCE_NUMBER)), jmsMessage.getStringProperty(SQSMessagingClientConstants.JMS_SQS_SEQUENCE_NUMBER));
        assertEquals(message.attributes().get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.MESSAGE_GROUP_ID)), jmsMessage.getStringProperty(SQSMessagingClientConstants.JMSX_GROUP_ID));
    }

    /**
     * Test ConvertToJMSMessage with byte message type
     */
    @Test
    public void testConvertToJMSMessageByteTypeAttribute() throws JMSException, IOException {

        /*
         * Set up consumer prefetch and mocks
         */
    	// Return message attributes with message type 'BYTE'
    	MessageAttributeValue messageAttributeValue = MessageAttributeValue.builder()
    			.stringValue(SQSMessage.BYTE_MESSAGE_TYPE)
    			.dataType(SQSMessagingClientConstants.STRING)
    			.build();

    	HashMap<String,MessageAttributeValue> messageAttributes = new HashMap<>();
    	messageAttributes.put(SQSMessage.JMS_SQS_MESSAGE_TYPE, messageAttributeValue);
    	
    	byte[] byteArray = new byte[] { 1, 0, 'a', 65 };
    	
    	Message message = createValidFifoMessage(1, "G")
    			.messageAttributes(messageAttributes)
    			.body(BinaryUtils.toBase64(byteArray))
    			.build();
        
        /*
         * Convert the SQS message to JMS Message
         */
        javax.jms.Message jmsMessage = consumerPrefetch.convertToJMSMessage(message);

        /*
         * Verify results
         */
        assertTrue(jmsMessage instanceof SQSBytesMessage);
        for (byte b : byteArray) {
            assertEquals(b, ((SQSBytesMessage)jmsMessage).readByte());
        }
        assertEquals(message.attributes().get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.MESSAGE_DEDUPLICATION_ID)), jmsMessage.getStringProperty(SQSMessagingClientConstants.JMS_SQS_DEDUPLICATION_ID));
        assertEquals(message.attributes().get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.SEQUENCE_NUMBER)), jmsMessage.getStringProperty(SQSMessagingClientConstants.JMS_SQS_SEQUENCE_NUMBER));
        assertEquals(message.attributes().get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.MESSAGE_GROUP_ID)), jmsMessage.getStringProperty(SQSMessagingClientConstants.JMSX_GROUP_ID));
    }

    /**
     * Test ConvertToJMSMessage with byte message that contains illegal sqs message body
     */
    @Test
    public void testConvertToJMSMessageByteTypeIllegalBody() throws JMSException, IOException {

        /*
         * Set up consumer prefetch and mocks
         */
    	// Return message attributes with message type 'BYTE'
    	MessageAttributeValue messageAttributeValue = MessageAttributeValue.builder()
    			.stringValue(SQSMessage.BYTE_MESSAGE_TYPE)
    			.dataType(SQSMessagingClientConstants.STRING)
    			.build();
    	
    	HashMap<String,MessageAttributeValue> messageAttributes = new HashMap<>();
    	messageAttributes.put(SQSMessage.JMS_SQS_MESSAGE_TYPE, messageAttributeValue);

    	// Return illegal message body for byte message type
    	Message message = createValidFifoMessage(1, "G")
    			.body("Text Message")
    			.messageAttributes(messageAttributes)
    			.build();

        /*
         * Convert the SQS message to JMS Message
         */
        try {
            consumerPrefetch.convertToJMSMessage(message);
            fail("Expect JMSException");
        } catch (JMSException jmse) {
            // Expected JMS exception
        }
    }

    /**
     * Test ConvertToJMSMessage with an object message
     */
    @Test
    public void testConvertToJMSMessageObjectTypeAttribute() throws JMSException, IOException {

        /*
         * Set up consumer prefetch and mocks
         */
    	// Return message attributes with message type 'OBJECT'
    	MessageAttributeValue messageAttributeValue = MessageAttributeValue.builder()
    			.stringValue(SQSMessage.OBJECT_MESSAGE_TYPE)
    			.dataType(SQSMessagingClientConstants.STRING)
    			.build();
    	
    	HashMap<String,MessageAttributeValue> messageAttributes = new HashMap<>();
    	messageAttributes.put(SQSMessage.JMS_SQS_MESSAGE_TYPE, messageAttributeValue);	

    	// Encode an object to byte array
    	Integer integer = Integer.valueOf(10);
    	ByteArrayOutputStream array = new ByteArrayOutputStream(10);
    	ObjectOutputStream oStream = new ObjectOutputStream(array);
    	oStream.writeObject(integer);
    	oStream.close();
    	
    	Message message = createValidFifoMessage(1, "G")
    			.messageAttributes(messageAttributes)
    			.body(BinaryUtils.toBase64(array.toByteArray()))
    			.build();

        /*
         * Convert the SQS message to JMS Message
         */
        javax.jms.Message jmsMessage = consumerPrefetch.convertToJMSMessage(message);

        /*
         * Verify results
         */
        assertTrue(jmsMessage instanceof SQSObjectMessage);
        assertEquals(integer, ((SQSObjectMessage) jmsMessage).getObject());
        assertEquals(message.attributes().get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.MESSAGE_DEDUPLICATION_ID)), jmsMessage.getStringProperty(SQSMessagingClientConstants.JMS_SQS_DEDUPLICATION_ID));
        assertEquals(message.attributes().get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.SEQUENCE_NUMBER)), jmsMessage.getStringProperty(SQSMessagingClientConstants.JMS_SQS_SEQUENCE_NUMBER));
        assertEquals(message.attributes().get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.MESSAGE_GROUP_ID)), jmsMessage.getStringProperty(SQSMessagingClientConstants.JMSX_GROUP_ID));
    }

    /**
     * Test ConvertToJMSMessage with an object message that contains illegal sqs message body
     */
    @Test
    public void testConvertToJMSMessageObjectIllegalBody() throws JMSException, IOException {

        /*
         * Set up consumer prefetch and mocks
         */
    	// Return message attributes with message type 'OBJECT'
    	MessageAttributeValue messageAttributeValue = MessageAttributeValue.builder()
    	.stringValue(SQSMessage.OBJECT_MESSAGE_TYPE)
    	.dataType(SQSMessagingClientConstants.STRING)
    	.build();
    	
    	HashMap<String,MessageAttributeValue> messageAttributes = new HashMap<>();
    	messageAttributes.put(SQSMessage.JMS_SQS_MESSAGE_TYPE, messageAttributeValue);	

    	Message message = createValidFifoMessage(1, "G")
    			.messageAttributes(messageAttributes)
    			.body("Some text that does not represent an object")
    			.build();

        /*
         * Convert the SQS message to JMS Message
         */
        ObjectMessage jmsMessage = (ObjectMessage) consumerPrefetch.convertToJMSMessage(message);

        /*
         * Verify results
         */
        try {
            jmsMessage.getObject();
            fail("Expect JMSException");
        } catch (JMSException jmse) {
            // Expected JMS exception
        }
    }

    /**
     * Test ConvertToJMSMessage with text message with text type attribute
     */
    @Test
    public void testConvertToJMSMessageTextTypeAttribute() throws JMSException, IOException {

        /*
         * Set up consumer prefetch and mocks
         */
    	// Return message attributes with message type 'TEXT'
    	MessageAttributeValue messageAttributeValue = MessageAttributeValue.builder()
    			.stringValue(SQSMessage.TEXT_MESSAGE_TYPE)
    			.dataType(SQSMessagingClientConstants.STRING)
    			.build();
    	
    	HashMap<String,MessageAttributeValue> messageAttributes = new HashMap<>();
    	messageAttributes.put(SQSMessage.JMS_SQS_MESSAGE_TYPE, messageAttributeValue);	

    	Message message = createValidFifoMessage(1, "G")
    			.messageAttributes(messageAttributes)
    			.body("MessageBody")
    			.build();
        
        /*
         * Convert the SQS message to JMS Message
         */
        javax.jms.Message jmsMessage = consumerPrefetch.convertToJMSMessage(message);

        /*
         * Verify results
         */
        assertTrue(jmsMessage instanceof SQSTextMessage);
        assertEquals(message.body(), "MessageBody");
        assertEquals(message.attributes().get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.MESSAGE_DEDUPLICATION_ID)), jmsMessage.getStringProperty(SQSMessagingClientConstants.JMS_SQS_DEDUPLICATION_ID));
        assertEquals(message.attributes().get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.SEQUENCE_NUMBER)), jmsMessage.getStringProperty(SQSMessagingClientConstants.JMS_SQS_SEQUENCE_NUMBER));
        assertEquals(message.attributes().get(MessageSystemAttributeName.fromValue(SQSMessagingClientConstants.MESSAGE_GROUP_ID)), jmsMessage.getStringProperty(SQSMessagingClientConstants.JMSX_GROUP_ID));
    }

    /*
     * Utility functions
     */

    private Builder createValidFifoMessage(int messageNumber, String groupId) {
        Map<String,String> mapAttributes = new HashMap<String, String>();
        mapAttributes.put(SQSMessagingClientConstants.APPROXIMATE_RECEIVE_COUNT, "1");
        mapAttributes.put(SQSMessagingClientConstants.SEQUENCE_NUMBER, "10000000000000000000" + messageNumber);
        mapAttributes.put(SQSMessagingClientConstants.MESSAGE_DEDUPLICATION_ID, "d" + messageNumber);
        mapAttributes.put(SQSMessagingClientConstants.MESSAGE_GROUP_ID, groupId);
        
        return Message.builder()
        		.receiptHandle("r" + messageNumber)
        		.attributesWithStrings(mapAttributes);
    }

}
