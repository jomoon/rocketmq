/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store.schedule;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.rocketmq.common.ConfigManager;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.TopicFilterType;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.topic.TopicValidator;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.common.message.MessageAccessor;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.running.RunningStats;
import org.apache.rocketmq.store.ConsumeQueue;
import org.apache.rocketmq.store.ConsumeQueueExt;
import org.apache.rocketmq.store.DefaultMessageStore;
import org.apache.rocketmq.store.MessageExtBrokerInner;
import org.apache.rocketmq.store.MessageStore;
import org.apache.rocketmq.store.PutMessageResult;
import org.apache.rocketmq.store.PutMessageStatus;
import org.apache.rocketmq.store.SelectMappedBufferResult;
import org.apache.rocketmq.store.config.StorePathConfigHelper;
// 通过DefaultMessageStore构造生成 load、start
public class ScheduleMessageService extends ConfigManager {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.STORE_LOGGER_NAME);
    // 首次延迟时间
    private static final long FIRST_DELAY_TIME = 1000L;
    private static final long DELAY_FOR_A_WHILE = 100L;
    // 发送异常后延迟该时间后再继续调度
    private static final long DELAY_FOR_A_PERIOD = 10000L;
    // 延迟级别，将延迟级别解析成 delayLevelTable {1:1000,2:5000,3:30000}
    private final ConcurrentMap<Integer /* level */, Long/* delay timeMillis */> delayLevelTable =
        new ConcurrentHashMap<Integer, Long>(32);
    // 延迟级别消息消费进度 每个level的偏移量
    private final ConcurrentMap<Integer /* level */, Long/* offset */> offsetTable =
        new ConcurrentHashMap<Integer, Long>(32);
    // 默认的消息存储器
    private final DefaultMessageStore defaultMessageStore;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private Timer timer;
    private MessageStore writeMessageStore;
    // 最大延迟级别
    private int maxDelayLevel;

    /**
     * 1.  构造
     * 2. load 进度加载和delayTable的数据构造
     * 3. start
     *
     * 设计关键点
     * 1. 定时消息单独一个主题: SCHEDULE_TOPIC_XXXX => TopicValidator.RMQ_SYS_SCHEDULE_TOPIC
     * 2.
     * @param defaultMessageStore
     */
    public ScheduleMessageService(final DefaultMessageStore defaultMessageStore) {
        this.defaultMessageStore = defaultMessageStore;
        this.writeMessageStore = defaultMessageStore;
    }

    public static int queueId2DelayLevel(final int queueId) {
        return queueId + 1;
    }

    public static int delayLevel2QueueId(final int delayLevel) {
        return delayLevel - 1;
    }

    /**
     * @param writeMessageStore the writeMessageStore to set
     */
    public void setWriteMessageStore(MessageStore writeMessageStore) {
        this.writeMessageStore = writeMessageStore;
    }

    public void buildRunningStats(HashMap<String, String> stats) {
        Iterator<Map.Entry<Integer, Long>> it = this.offsetTable.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> next = it.next();
            int queueId = delayLevel2QueueId(next.getKey());
            long delayOffset = next.getValue();
            long maxOffset = this.defaultMessageStore.getMaxOffsetInQueue(TopicValidator.RMQ_SYS_SCHEDULE_TOPIC, queueId);
            String value = String.format("%d,%d", delayOffset, maxOffset);
            String key = String.format("%s_%d", RunningStats.scheduleMessageOffset.name(), next.getKey());
            stats.put(key, value);
        }
    }

    private void updateOffset(int delayLevel, long offset) {
        this.offsetTable.put(delayLevel, offset);
    }

    public long computeDeliverTimestamp(final int delayLevel, final long storeTimestamp) {
        Long time = this.delayLevelTable.get(delayLevel);
        if (time != null) {
            return time + storeTimestamp;
        }

        return storeTimestamp + 1000;
    }

    /**
     * 创建对应的定时任务，启动定时任务持久化消息队列存储进度
     */
    public void start() {
        if (started.compareAndSet(false, true)) {
            super.load();
            this.timer = new Timer("ScheduleMessageTimerThread", true);
            // 根据延迟队列创建定时任务,启动定时任务持久化延迟消息队列进度存储
            for (Map.Entry<Integer, Long> entry : this.delayLevelTable.entrySet()) {
                Integer level = entry.getKey();
                Long timeDelay = entry.getValue();
                // 获取当前消息队列的偏移量，如果不存在那么就使用0
                Long offset = this.offsetTable.get(level);
                if (null == offset) {
                    offset = 0L;
                }

                if (timeDelay != null) {
                    // 加入到定时任务持久化队列
                    this.timer.schedule(new DeliverDelayedMessageTimerTask(level, offset), FIRST_DELAY_TIME);
                }
            }
            // 创建延迟队列 每隔10s持久化一次延迟队列的消费进度
            this.timer.scheduleAtFixedRate(new TimerTask() {

                @Override
                public void run() {
                    try {
                        if (started.get()) {
                            // 持久化消费进度
                            ScheduleMessageService.this.persist();
                        }
                    } catch (Throwable e) {
                        log.error("scheduleAtFixedRate flush exception", e);
                    }
                    // 延迟10s，频率 flushDelayOffsetInterval
                }
            }, 10000, this.defaultMessageStore.getMessageStoreConfig().getFlushDelayOffsetInterval());
        }
    }

    public void shutdown() {
        if (this.started.compareAndSet(true, false)) {
            if (null != this.timer)
                this.timer.cancel();
        }

    }

    public boolean isStarted() {
        return started.get();
    }

    public int getMaxDelayLevel() {
        return maxDelayLevel;
    }

    public String encode() {
        return this.encode(false);
    }

    // 完成延迟消息消费队列的进度加载和delayLevelTable的数据构造
    public boolean load() {
        // 进度加载
        boolean result = super.load();
        // 延迟队列的数据构造
        result = result && this.parseDelayLevel();
        // 矫正延迟队列偏移量
        result = result && this.correctDelayOffset();
        return result;
    }

    public boolean correctDelayOffset() {
        try {
            for (int delayLevel : delayLevelTable.keySet()) {
                ConsumeQueue cq =
                    ScheduleMessageService.this.defaultMessageStore.findConsumeQueue(TopicValidator.RMQ_SYS_SCHEDULE_TOPIC,
                        delayLevel2QueueId(delayLevel));
                Long currentDelayOffset = offsetTable.get(delayLevel);
                if (currentDelayOffset == null || cq == null) {
                    continue;
                }
                long correctDelayOffset = currentDelayOffset;
                long cqMinOffset = cq.getMinOffsetInQueue();
                long cqMaxOffset = cq.getMaxOffsetInQueue();
                if (currentDelayOffset < cqMinOffset) {
                    correctDelayOffset = cqMinOffset;
                    log.error("schedule CQ offset invalid. offset={}, cqMinOffset={}, cqMaxOffset={}, queueId={}",
                        currentDelayOffset, cqMinOffset, cqMaxOffset, cq.getQueueId());
                }

                if (currentDelayOffset > cqMaxOffset) {
                    correctDelayOffset = cqMaxOffset;
                    log.error("schedule CQ offset invalid. offset={}, cqMinOffset={}, cqMaxOffset={}, queueId={}",
                        currentDelayOffset, cqMinOffset, cqMaxOffset, cq.getQueueId());
                }
                if (correctDelayOffset != currentDelayOffset) {
                    log.error("correct delay offset [ delayLevel {} ] from {} to {}", delayLevel, currentDelayOffset, correctDelayOffset);
                    offsetTable.put(delayLevel, correctDelayOffset);
                }
            }
        } catch (Exception e) {
            log.error("correctDelayOffset exception", e);
            return false;
        }
        return true;
    }

    @Override
    public String configFilePath() {
        // rootDir + File.separator + "config" + File.separator + "delayOffset.json";
        return StorePathConfigHelper.getDelayOffsetStorePath(this.defaultMessageStore.getMessageStoreConfig()
            .getStorePathRootDir());
    }

    @Override
    public void decode(String jsonString) {
        if (jsonString != null) {
            DelayOffsetSerializeWrapper delayOffsetSerializeWrapper =
                DelayOffsetSerializeWrapper.fromJson(jsonString, DelayOffsetSerializeWrapper.class);
            if (delayOffsetSerializeWrapper != null) {
                this.offsetTable.putAll(delayOffsetSerializeWrapper.getOffsetTable());
            }
        }
    }

    public String encode(final boolean prettyFormat) {
        DelayOffsetSerializeWrapper delayOffsetSerializeWrapper = new DelayOffsetSerializeWrapper();
        delayOffsetSerializeWrapper.setOffsetTable(this.offsetTable);
        return delayOffsetSerializeWrapper.toJson(prettyFormat);
    }

    public boolean parseDelayLevel() {
        HashMap<String, Long> timeUnitTable = new HashMap<String, Long>();
        timeUnitTable.put("s", 1000L);
        timeUnitTable.put("m", 1000L * 60);
        timeUnitTable.put("h", 1000L * 60 * 60);
        timeUnitTable.put("d", 1000L * 60 * 60 * 24);

        String levelString = this.defaultMessageStore.getMessageStoreConfig().getMessageDelayLevel();
        try {
            String[] levelArray = levelString.split(" ");
            for (int i = 0; i < levelArray.length; i++) {
                String value = levelArray[i];
                String ch = value.substring(value.length() - 1);
                Long tu = timeUnitTable.get(ch);

                int level = i + 1;
                if (level > this.maxDelayLevel) {
                    this.maxDelayLevel = level;
                }
                long num = Long.parseLong(value.substring(0, value.length() - 1));
                long delayTimeMillis = tu * num;
                // 延迟级别数据的构造
                this.delayLevelTable.put(level, delayTimeMillis);
            }
        } catch (Exception e) {
            log.error("parseDelayLevel exception", e);
            log.info("levelString String = {}", levelString);
            return false;
        }

        return true;
    }

    class DeliverDelayedMessageTimerTask extends TimerTask {
        private final int delayLevel;
        private final long offset;

        public DeliverDelayedMessageTimerTask(int delayLevel, long offset) {
            this.delayLevel = delayLevel;
            this.offset = offset;
        }

        @Override
        public void run() {
            try {
                if (isStarted()) {
                    // 核心
                    this.executeOnTimeup();
                }
            } catch (Exception e) {
                // XXX: warn and notify me
                log.error("ScheduleMessageService, executeOnTimeup exception", e);
                ScheduleMessageService.this.timer.schedule(new DeliverDelayedMessageTimerTask(
                    this.delayLevel, this.offset), DELAY_FOR_A_PERIOD);
            }
        }

        /**
         * @return
         */
        private long correctDeliverTimestamp(final long now, final long deliverTimestamp) {

            long result = deliverTimestamp;

            long maxTimestamp = now + ScheduleMessageService.this.delayLevelTable.get(this.delayLevel);
            if (deliverTimestamp > maxTimestamp) {
                result = now;
            }

            return result;
        }
        // 核心
        public void executeOnTimeup() {
            // 根据队列ID与延迟队列主题查找消息消费队列，如果找到，说明目前并不存在该延迟级别的消息，忽略本次任务，根据延时级别创建下次调度任务即可
            ConsumeQueue cq =
                ScheduleMessageService.this.defaultMessageStore.findConsumeQueue(TopicValidator.RMQ_SYS_SCHEDULE_TOPIC,
                    delayLevel2QueueId(delayLevel));

            long failScheduleOffset = offset;

            if (cq != null) {
                // 根据偏移量从消息消费队列中获取有效消息
                SelectMappedBufferResult bufferCQ = cq.getIndexBuffer(this.offset);
                if (bufferCQ != null) {
                    try {
                        long nextOffset = offset;
                        int i = 0;
                        ConsumeQueueExt.CqExtUnit cqExtUnit = new ConsumeQueueExt.CqExtUnit();
                        // 遍历ConsumeQueue，默认条目20字节
                        for (; i < bufferCQ.getSize(); i += ConsumeQueue.CQ_STORE_UNIT_SIZE) {
                            // 偏移量
                            long offsetPy = bufferCQ.getByteBuffer().getLong();
                            // 消息长度
                            int sizePy = bufferCQ.getByteBuffer().getInt();
                            // tag hashcode
                            long tagsCode = bufferCQ.getByteBuffer().getLong();

                            if (cq.isExtAddr(tagsCode)) {
                                if (cq.getExt(tagsCode, cqExtUnit)) {
                                    tagsCode = cqExtUnit.getTagsCode();
                                } else {
                                    //can't find ext content.So re compute tags code.
                                    log.error("[BUG] can't find consume queue extend file content!addr={}, offsetPy={}, sizePy={}",
                                        tagsCode, offsetPy, sizePy);
                                    long msgStoreTime = defaultMessageStore.getCommitLog().pickupStoreTimestamp(offsetPy, sizePy);
                                    tagsCode = computeDeliverTimestamp(delayLevel, msgStoreTime);
                                }
                            }

                            long now = System.currentTimeMillis();
                            long deliverTimestamp = this.correctDeliverTimestamp(now, tagsCode);

                            nextOffset = offset + (i / ConsumeQueue.CQ_STORE_UNIT_SIZE);

                            long countdown = deliverTimestamp - now;

                            if (countdown <= 0) {
                                // 根据偏移量和大小 commitlog 文件中找消息
                                MessageExt msgExt =
                                    ScheduleMessageService.this.defaultMessageStore.lookMessageByOffset(
                                        offsetPy, sizePy);

                                if (msgExt != null) {
                                    try {
                                        MessageExtBrokerInner msgInner = this.messageTimeup(msgExt);
                                        if (TopicValidator.RMQ_SYS_TRANS_HALF_TOPIC.equals(msgInner.getTopic())) {
                                            log.error("[BUG] the real topic of schedule msg is {}, discard the msg. msg={}",
                                                msgInner.getTopic(), msgInner);
                                            continue;
                                        }
                                        // 将消息再次存入commitlog到达真正的主题
                                        PutMessageResult putMessageResult =
                                            ScheduleMessageService.this.writeMessageStore
                                                .putMessage(msgInner);

                                        if (putMessageResult != null
                                            && putMessageResult.getPutMessageStatus() == PutMessageStatus.PUT_OK) {
                                            if (ScheduleMessageService.this.defaultMessageStore.getMessageStoreConfig().isEnableScheduleMessageStats()) {
                                                ScheduleMessageService.this.defaultMessageStore.getBrokerStatsManager().incQueueGetNums(MixAll.SCHEDULE_CONSUMER_GROUP, TopicValidator.RMQ_SYS_SCHEDULE_TOPIC, delayLevel - 1, putMessageResult.getAppendMessageResult().getMsgNum());
                                                ScheduleMessageService.this.defaultMessageStore.getBrokerStatsManager().incQueueGetSize(MixAll.SCHEDULE_CONSUMER_GROUP, TopicValidator.RMQ_SYS_SCHEDULE_TOPIC, delayLevel - 1, putMessageResult.getAppendMessageResult().getWroteBytes());
                                                ScheduleMessageService.this.defaultMessageStore.getBrokerStatsManager().incGroupGetNums(MixAll.SCHEDULE_CONSUMER_GROUP, TopicValidator.RMQ_SYS_SCHEDULE_TOPIC, putMessageResult.getAppendMessageResult().getMsgNum());
                                                ScheduleMessageService.this.defaultMessageStore.getBrokerStatsManager().incGroupGetSize(MixAll.SCHEDULE_CONSUMER_GROUP, TopicValidator.RMQ_SYS_SCHEDULE_TOPIC, putMessageResult.getAppendMessageResult().getWroteBytes());
                                                ScheduleMessageService.this.defaultMessageStore.getBrokerStatsManager().incTopicPutNums(msgInner.getTopic(), putMessageResult.getAppendMessageResult().getMsgNum(), 1);
                                                ScheduleMessageService.this.defaultMessageStore.getBrokerStatsManager().incTopicPutSize(msgInner.getTopic(),
                                                    putMessageResult.getAppendMessageResult().getWroteBytes());
                                                ScheduleMessageService.this.defaultMessageStore.getBrokerStatsManager().incBrokerPutNums(putMessageResult.getAppendMessageResult().getMsgNum());
                                            }
                                            continue;
                                        } else {
                                            // XXX: warn and notify me
                                            log.error(
                                                "ScheduleMessageService, a message time up, but reput it failed, topic: {} msgId {}",
                                                msgExt.getTopic(), msgExt.getMsgId());
                                            ScheduleMessageService.this.timer.schedule(
                                                new DeliverDelayedMessageTimerTask(this.delayLevel,
                                                    nextOffset), DELAY_FOR_A_PERIOD);
                                            ScheduleMessageService.this.updateOffset(this.delayLevel,
                                                nextOffset);
                                            return;
                                        }
                                    } catch (Exception e) {
                                        /*
                                         * XXX: warn and notify me
                                         */
                                        log.error(
                                            "ScheduleMessageService, messageTimeup execute error, drop it. msgExt={}, nextOffset={}, offsetPy={}, sizePy={}", msgExt, nextOffset, offsetPy, sizePy, e);
                                    }
                                }
                            } else {
                                // 根据延迟时间创建下一个定时器
                                ScheduleMessageService.this.timer.schedule(
                                    new DeliverDelayedMessageTimerTask(this.delayLevel, nextOffset),
                                    countdown);
                                ScheduleMessageService.this.updateOffset(this.delayLevel, nextOffset);
                                return;
                            }
                        } // end of for

                        nextOffset = offset + (i / ConsumeQueue.CQ_STORE_UNIT_SIZE);
                        ScheduleMessageService.this.timer.schedule(new DeliverDelayedMessageTimerTask(
                            this.delayLevel, nextOffset), DELAY_FOR_A_WHILE);
                        ScheduleMessageService.this.updateOffset(this.delayLevel, nextOffset);
                        return;
                    } finally {

                        bufferCQ.release();
                    }
                } // end of if (bufferCQ != null)
                else {

                    long cqMinOffset = cq.getMinOffsetInQueue();
                    long cqMaxOffset = cq.getMaxOffsetInQueue();
                    if (offset < cqMinOffset) {
                        failScheduleOffset = cqMinOffset;
                        log.error("schedule CQ offset invalid. offset={}, cqMinOffset={}, cqMaxOffset={}, queueId={}",
                            offset, cqMinOffset, cqMaxOffset, cq.getQueueId());
                    }

                    if (offset > cqMaxOffset) {
                        failScheduleOffset = cqMaxOffset;
                        log.error("schedule CQ offset invalid. offset={}, cqMinOffset={}, cqMaxOffset={}, queueId={}",
                            offset, cqMinOffset, cqMaxOffset, cq.getQueueId());
                    }
                }
            } // end of if (cq != null)

            ScheduleMessageService.this.timer.schedule(new DeliverDelayedMessageTimerTask(this.delayLevel,
                failScheduleOffset), DELAY_FOR_A_WHILE);
        }

        /**
         * 根据消息构造 MessageExtBrokerInner
         *
         * @param msgExt
         * @return
         */
        private MessageExtBrokerInner messageTimeup(MessageExt msgExt) {
            MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
            msgInner.setBody(msgExt.getBody());
            msgInner.setFlag(msgExt.getFlag());
            MessageAccessor.setProperties(msgInner, msgExt.getProperties());

            TopicFilterType topicFilterType = MessageExt.parseTopicFilterType(msgInner.getSysFlag());
            long tagsCodeValue =
                MessageExtBrokerInner.tagsString2tagsCode(topicFilterType, msgInner.getTags());
            msgInner.setTagsCode(tagsCodeValue);
            msgInner.setPropertiesString(MessageDecoder.messageProperties2String(msgExt.getProperties()));

            msgInner.setSysFlag(msgExt.getSysFlag());
            msgInner.setBornTimestamp(msgExt.getBornTimestamp());
            msgInner.setBornHost(msgExt.getBornHost());
            msgInner.setStoreHost(msgExt.getStoreHost());
            msgInner.setReconsumeTimes(msgExt.getReconsumeTimes());

            msgInner.setWaitStoreMsgOK(false);
            // 清除消息的延迟级别属性
            MessageAccessor.clearProperty(msgInner, MessageConst.PROPERTY_DELAY_TIME_LEVEL);
            // 恢复原先的消息主题与消息消费队列
            msgInner.setTopic(msgInner.getProperty(MessageConst.PROPERTY_REAL_TOPIC));

            String queueIdStr = msgInner.getProperty(MessageConst.PROPERTY_REAL_QUEUE_ID);
            int queueId = Integer.parseInt(queueIdStr);
            msgInner.setQueueId(queueId);

            return msgInner;
        }
    }
}
