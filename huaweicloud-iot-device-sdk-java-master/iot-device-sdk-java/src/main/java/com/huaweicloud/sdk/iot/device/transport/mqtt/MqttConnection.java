package com.huaweicloud.sdk.iot.device.transport.mqtt;

import com.huaweicloud.sdk.iot.device.client.ClientConf;
import com.huaweicloud.sdk.iot.device.client.listener.DefaultPublishListenerImpl;
import com.huaweicloud.sdk.iot.device.client.listener.DefaultSubscribeListenerImpl;
import com.huaweicloud.sdk.iot.device.transport.ActionListener;
import com.huaweicloud.sdk.iot.device.transport.ConnectActionListener;
import com.huaweicloud.sdk.iot.device.transport.ConnectListener;
import com.huaweicloud.sdk.iot.device.transport.Connection;
import com.huaweicloud.sdk.iot.device.transport.RawMessage;
import com.huaweicloud.sdk.iot.device.transport.RawMessageListener;
import com.huaweicloud.sdk.iot.device.utils.ExceptionUtil;
import com.huaweicloud.sdk.iot.device.utils.IotUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import javax.net.ssl.SSLContext;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * mqtt连接
 */
public class MqttConnection implements Connection {

    private static final int DEFAULT_QOS = 1;

    private static final int DEFAULT_CONNECT_TIMEOUT = 60;

    private static final int DEFAULT_KEEPLIVE = 120;

    private static final String CONNECT_TYPE = "0";

    private static final String CHECK_TIMESTAMP = "0";

    private ClientConf clientConf;

    private boolean connectFinished = false;

    private MqttAsyncClient mqttAsyncClient;

    private ConnectListener connectListener;

    private ConnectActionListener connectActionListener;

    private RawMessageListener rawMessageListener;

    private int connectResultCode;

    private static final Logger log = LogManager.getLogger(MqttConnection.class);

    public MqttConnection(ClientConf clientConf, RawMessageListener rawMessageListener) {
        this.clientConf = clientConf;
        this.rawMessageListener = rawMessageListener;
    }

    private MqttCallback callback = new MqttCallbackExtended() {

        @Override
        public void connectionLost(Throwable cause) {
            log.error("Connection lost.", cause);
            if (connectListener != null) {
                connectListener.connectionLost(cause);
            }

        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            log.info("messageArrived topic =  " + topic + ", msg = " + message.toString());
            RawMessage rawMessage = new RawMessage(topic, message.toString());
            try {
                if (rawMessageListener != null) {
                    rawMessageListener.onMessageReceived(rawMessage);
                }

            } catch (Exception e) {
                log.error(ExceptionUtil.getBriefStackTrace(e));
            }

        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {

        }

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            log.info("Mqtt client connected. address :" + serverURI);

            if (connectListener != null) {
                connectListener.connectComplete(reconnect, serverURI);
            }
        }
    };

    @Override
    public int connect() {

        try {

            String timeStamp = ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("UTC"))
                    .format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
            String clientId = null;
            if (clientConf.getScopeId() == null) {
                clientId = clientConf.getDeviceId() + "_" + CONNECT_TYPE + "_" + CHECK_TIMESTAMP + "_" + timeStamp;
            } else {
                clientId = clientConf.getDeviceId() + "_" + CONNECT_TYPE + "_" + clientConf.getScopeId();
            }

            try {
                mqttAsyncClient = new MqttAsyncClient(clientConf.getServerUri(), clientId, new MemoryPersistence());
            } catch (MqttException e) {
                log.error(ExceptionUtil.getBriefStackTrace(e));
            }

            DisconnectedBufferOptions bufferOptions = new DisconnectedBufferOptions();
            bufferOptions.setBufferEnabled(true);
            if (clientConf.getOfflineBufferSize() != null) {
                bufferOptions.setBufferSize(clientConf.getOfflineBufferSize());
            }

            mqttAsyncClient.setBufferOpts(bufferOptions);

            MqttConnectOptions options = new MqttConnectOptions();
            if (clientConf.getServerUri().contains("ssl:")) {

                try {
                    SSLContext sslContext = IotUtil.getSSLContext(clientConf);
                    options.setSocketFactory(sslContext.getSocketFactory());
                    options.setHttpsHostnameVerificationEnabled(false);
                } catch (Exception e) {
                    log.error(ExceptionUtil.getBriefStackTrace(e));
                    return -1;
                }
            }

            options.setCleanSession(false);
            options.setUserName(clientConf.getDeviceId());

            if (clientConf.getSecret() != null && !clientConf.getSecret().isEmpty()) {
                String passWord = IotUtil.sha256_mac(clientConf.getSecret(), timeStamp);
                options.setPassword(passWord.toCharArray());
            }

            options.setConnectionTimeout(DEFAULT_CONNECT_TIMEOUT);
            options.setKeepAliveInterval(DEFAULT_KEEPLIVE);
            options.setAutomaticReconnect(true);
            mqttAsyncClient.setCallback(callback);

            log.info("try to connect to " + clientConf.getServerUri());

            mqttAsyncClient.connect(options, null, getCallback());

            synchronized (this) {

                while (!connectFinished) {

                    try {
                        wait(DEFAULT_CONNECT_TIMEOUT * 1000);
                    } catch (InterruptedException e) {
                        log.error(ExceptionUtil.getBriefStackTrace(e));
                    }
                }
            }

        } catch (MqttException e) {
            log.error(ExceptionUtil.getBriefStackTrace(e));

        }

        if (mqttAsyncClient.isConnected()) {
            return 0;
        }

        // 处理paho返回的错误码为0的场景
        if (connectResultCode == 0) {
            log.error("Client encountered an exception");
            return -1;
        }

        return connectResultCode;
    }

    private IMqttActionListener getCallback() {
        return new IMqttActionListener() {
            @Override
            public void onSuccess(IMqttToken iMqttToken) {

                log.info("connect success " + clientConf.getServerUri());

                if (connectActionListener != null) {
                    connectActionListener.onSuccess(iMqttToken);
                }

                synchronized (MqttConnection.this) {
                    connectFinished = true;
                    MqttConnection.this.notifyAll();
                }
            }

            @Override
            public void onFailure(IMqttToken iMqttToken, Throwable throwable) {
                log.info("connect failed " + throwable.toString());
                MqttException me = (MqttException) throwable;
                connectResultCode = me.getReasonCode();

                if (connectActionListener != null) {
                    connectActionListener.onFailure(iMqttToken, throwable);
                }

                synchronized (MqttConnection.this) {
                    connectFinished = true;
                    MqttConnection.this.notifyAll();
                }
            }
        };
    }

    @Override
    public void publishMessage(RawMessage message, ActionListener listener) {

        try {
            MqttMessage mqttMessage = new MqttMessage(message.getPayload());
            mqttMessage.setQos(message.getQos() == 0 ? 0 : DEFAULT_QOS);

            DefaultPublishListenerImpl defaultPublishListener = new DefaultPublishListenerImpl(listener, message);

            mqttAsyncClient.publish(message.getTopic(), mqttMessage, message.getTopic(), defaultPublishListener);
            log.info("publish message topic =  " + message.getTopic() + ", msg = " + message.toString());
        } catch (MqttException e) {
            log.error(ExceptionUtil.getBriefStackTrace(e));
            if (listener != null) {
                listener.onFailure(null, e);
            }
        }
    }

    public void close() {

        if (mqttAsyncClient.isConnected()) {
            try {
                mqttAsyncClient.disconnect();
            } catch (MqttException e) {
                log.error(ExceptionUtil.getBriefStackTrace(e));
            }
        }
    }

    @Override
    public boolean isConnected() {
        if (mqttAsyncClient == null) {
            return false;
        }
        return mqttAsyncClient.isConnected();
    }

    @Override
    public void setConnectListener(ConnectListener connectListener) {
        this.connectListener = connectListener;
    }

    @Override
    public void setConnectActionListener(ConnectActionListener connectActionListener) {
        this.connectActionListener = connectActionListener;
    }

    public void setRawMessageListener(RawMessageListener rawMessageListener) {
        this.rawMessageListener = rawMessageListener;
    }

    /**
     * 订阅指定主题
     *
     * @param topic 主题
     */
    public void subscribeTopic(String topic, ActionListener listener, int qos) {

        DefaultSubscribeListenerImpl defaultSubscribeListener = new DefaultSubscribeListenerImpl(topic, listener);

        try {
            mqttAsyncClient.subscribe(topic, qos, null, defaultSubscribeListener);
        } catch (MqttException e) {
            log.error(ExceptionUtil.getBriefStackTrace(e));
            if (listener != null) {
                listener.onFailure(topic, e);
            }
        }

    }

}
