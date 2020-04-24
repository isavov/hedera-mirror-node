package com.hedera.mirror.importer.parser.record.pubsub;

/*-
 * ‌
 * Hedera Mirror Node
 * ​
 * Copyright (C) 2019 - 2020 Hedera Hashgraph, LLC
 * ​
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
 * ‍
 */

import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import javax.inject.Named;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.annotation.Conditional;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;

import com.hedera.mirror.importer.addressbook.NetworkAddressBook;
import com.hedera.mirror.importer.domain.EntityId;
import com.hedera.mirror.importer.exception.ImporterException;
import com.hedera.mirror.importer.exception.ParserException;
import com.hedera.mirror.importer.parser.domain.PubSubMessage;
import com.hedera.mirror.importer.parser.domain.RecordItem;
import com.hedera.mirror.importer.parser.record.NonFeeTransferExtractionStrategy;
import com.hedera.mirror.importer.parser.record.RecordItemListener;
import com.hedera.mirror.importer.parser.record.transactionhandler.TransactionHandler;
import com.hedera.mirror.importer.parser.record.transactionhandler.TransactionHandlerFactory;
import com.hedera.mirror.importer.util.Utility;

@Log4j2
@Named
@RequiredArgsConstructor
@Conditional(PubSubEnabledCondition.class)
public class PubSubRecordItemListener implements RecordItemListener {

    private final MessageChannel pubsubOutputChannel;
    private final NetworkAddressBook networkAddressBook;
    private final NonFeeTransferExtractionStrategy nonFeeTransfersExtractor;
    private final TransactionHandlerFactory transactionHandlerFactory;

    @Override
    public void onItem(RecordItem recordItem) throws ImporterException {
        TransactionBody body = recordItem.getTransactionBody();
        TransactionRecord txRecord = recordItem.getRecord();
        TransactionHandler transactionHandler = transactionHandlerFactory.create(body);
        log.trace("Storing transaction body: {}", () -> Utility.printProtoMessage(body));
        long consensusTimestamp = Utility.timeStampInNanos(txRecord.getConsensusTimestamp());
        EntityId entity = transactionHandler.getEntityId(recordItem);
        PubSubMessage pubSubMessage = buildPubSubMessage(consensusTimestamp, entity, recordItem);
        try {
            pubsubOutputChannel.send(MessageBuilder.withPayload(pubSubMessage).build());
        } catch (Exception e) {
            // This will make RecordFileParser to retry whole file, thus sending duplicates of previous transactions
            // in this file. In needed in future, this can be optimized to resend only the txns with consensusTimestamp
            // greater than that of last correctly sent txn.
            throw new ParserException("Error sending transaction to pubsub", e);
        }
        log.debug("Published transaction : {}", consensusTimestamp);
        if (NetworkAddressBook.isAddressBook(entity)) {
            networkAddressBook.updateFrom(body);
        }
    }

    private PubSubMessage buildPubSubMessage(long consensusTimestamp, EntityId entity, RecordItem recordItem) {
        Transaction transaction = recordItem.getTransaction().toBuilder()
                .clearBodyBytes()
                .setBody(recordItem.getTransactionBody()) // setting deprecated field makes json conversion easier
                .build();
        var nonFeeTransfers = addNonFeeTransfers(recordItem.getTransactionBody(), recordItem.getRecord());
        return new PubSubMessage(consensusTimestamp, entity, recordItem.getTransactionType(), transaction,
                recordItem.getRecord(), nonFeeTransfers);
    }

    /**
     * Set of explicit transfers in the transaction.
     */
    private Iterable<AccountAmount> addNonFeeTransfers(TransactionBody body, TransactionRecord transactionRecord) {
        var nonFeeTransfers = nonFeeTransfersExtractor.extractNonFeeTransfers(body, transactionRecord);
        if (!nonFeeTransfers.iterator().hasNext()) { // return null if empty
            return null;
        }
        return nonFeeTransfers;
    }
}

