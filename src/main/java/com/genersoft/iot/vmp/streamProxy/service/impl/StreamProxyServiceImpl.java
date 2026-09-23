package com.genersoft.iot.vmp.streamProxy.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.genersoft.iot.vmp.common.StreamInfo;
import com.genersoft.iot.vmp.common.enums.ChannelDataType;
import com.genersoft.iot.vmp.common.enums.MediaStreamUtil;
import com.genersoft.iot.vmp.conf.UserSetting;
import com.genersoft.iot.vmp.conf.exception.ControllerException;
import com.genersoft.iot.vmp.gb28181.bean.CommonGBChannel;
import com.genersoft.iot.vmp.gb28181.service.IGbChannelService;
import com.genersoft.iot.vmp.media.bean.MediaServer;
import com.genersoft.iot.vmp.media.event.media.MediaArrivalEvent;
import com.genersoft.iot.vmp.media.event.media.MediaDepartureEvent;
import com.genersoft.iot.vmp.media.event.media.MediaNotFoundEvent;
import com.genersoft.iot.vmp.media.event.mediaServer.MediaServerOfflineEvent;
import com.genersoft.iot.vmp.media.event.mediaServer.MediaServerOnlineEvent;
import com.genersoft.iot.vmp.media.service.IMediaServerService;
import com.genersoft.iot.vmp.media.zlm.dto.hook.OriginType;
import com.genersoft.iot.vmp.service.bean.ErrorCallback;
import com.genersoft.iot.vmp.service.bean.InviteErrorCode;
import com.genersoft.iot.vmp.storager.IRedisCatchStorage;
import com.genersoft.iot.vmp.streamProxy.bean.StreamProxy;
import com.genersoft.iot.vmp.streamProxy.dao.StreamProxyMapper;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyPlayService;
import com.genersoft.iot.vmp.streamProxy.service.IStreamProxyService;
import com.genersoft.iot.vmp.utils.DateUtil;
import com.genersoft.iot.vmp.vmanager.bean.ErrorCode;
import com.genersoft.iot.vmp.vmanager.bean.ResourceBaseInfo;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 视频代理业务
 */
@Slf4j
@Service
public class StreamProxyServiceImpl implements IStreamProxyService {

    @Autowired
    private StreamProxyMapper streamProxyMapper;

    @Autowired
    private IRedisCatchStorage redisCatchStorage;

    @Autowired
    private UserSetting userSetting;

    @Autowired
    private IStreamProxyPlayService playService;

    @Autowired
    private IMediaServerService mediaServerService;

    @Autowired
    private IGbChannelService gbChannelService;

    @Autowired
    DataSourceTransactionManager dataSourceTransactionManager;

    @Autowired
    TransactionDefinition transactionDefinition;

    private final AtomicBoolean idleCheckRunning = new AtomicBoolean(false);

    private final Set<Integer> idleCheckInProgress = ConcurrentHashMap.newKeySet();

    private volatile long lastIdleCheckTime = 0;

    /**
     * 流到来的处理
     */
    @Async
    @Transactional
    @org.springframework.context.event.EventListener
    public void onApplicationEvent(MediaArrivalEvent event) {
        if ("rtsp".equals(event.getSchema())) {
            streamChangeHandler(event.getApp(), event.getStream(), event.getMediaServer().getId(), true);
        }
    }

    /**
     * 流离开的处理
     */
    @Async
    @EventListener
    @Transactional
    public void onApplicationEvent(MediaDepartureEvent event) {
        if ("rtsp".equals(event.getSchema())) {
            streamChangeHandler(event.getApp(), event.getStream(), event.getMediaServer().getId(), false);
        }
    }

    /**
     * 流未找到的处理
     */
    @Async
    @EventListener
    public void onApplicationEvent(MediaNotFoundEvent event) {
        if (MediaStreamUtil.isKeywords(event.getApp())) {
            return;
        }
        // 拉流代理
        StreamProxy streamProxyByAppAndStream = getStreamProxyByAppAndStream(event.getApp(), event.getStream());
        if (streamProxyByAppAndStream != null && streamProxyByAppAndStream.isEnableDisableNoneReader()) {
            startByAppAndStream(event.getApp(), event.getStream(), ((code, msg, data) -> {
                log.info("[拉流代理] 自动点播成功， app： {}， stream: {}", event.getApp(), event.getStream());
            }));
        }
    }

    /**
     * 流媒体节点上线
     */
    @Async
    @EventListener
    @Transactional
    public void onApplicationEvent(MediaServerOnlineEvent event) {
        zlmServerOnline(event.getMediaServer());
    }

    /**
     * 流媒体节点离线
     */
    @Async
    @EventListener
    @Transactional
    public void onApplicationEvent(MediaServerOfflineEvent event) {
        zlmServerOffline(event.getMediaServer());
    }


    @Override
    @Transactional
    public void add(StreamProxy streamProxy) {
        StreamProxy streamProxyInDb = streamProxyMapper.selectOneByAppAndStream(streamProxy.getApp(), streamProxy.getStream());
        if (streamProxyInDb != null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "APP+STREAM已经存在");
        }
        if (streamProxy.getGbDeviceId() != null) {
            gbChannelService.add(streamProxy.buildCommonGBChannel());
        }
        streamProxy.setCreateTime(DateUtil.getNow());
        streamProxy.setUpdateTime(DateUtil.getNow());
        streamProxyMapper.add(streamProxy);
        streamProxy.setDataType(ChannelDataType.STREAM_PROXY);
        streamProxy.setDataDeviceId(streamProxy.getId());
    }

    @Override
    public void delete(int id) {
        StreamProxy streamProxy = getStreamProxy(id);
        if (streamProxy == null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "代理不存在");
        }
        delete(streamProxy);
    }

    private void delete(StreamProxy streamProxy) {
        Assert.notNull(streamProxy, "代理不可为NULL");
        if (streamProxy.getPulling() != null && streamProxy.getPulling()) {
            playService.stopProxy(streamProxy);
        }
        if (streamProxy.getGbId() > 0) {
            gbChannelService.delete(streamProxy.getGbId());
        }
        streamProxyMapper.delete(streamProxy.getId());
    }

    @Override
    @Transactional
    public void delteByAppAndStream(String app, String stream) {
        StreamProxy streamProxy = streamProxyMapper.selectOneByAppAndStream(app, stream);
        if (streamProxy == null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "代理不存在");
        }
        delete(streamProxy);
    }

    /**
     * 更新代理流
     */
    @Override
    public boolean update(StreamProxy streamProxy) {
        streamProxy.setUpdateTime(DateUtil.getNow());
        StreamProxy streamProxyInDb = streamProxyMapper.select(streamProxy.getId());
        if (streamProxyInDb == null) {
            throw new ControllerException(ErrorCode.ERROR100.getCode(), "代理不存在");
        }
        int updateResult = streamProxyMapper.update(streamProxy);
        if (updateResult > 0 && !ObjectUtils.isEmpty(streamProxy.getGbDeviceId())) {
            if (streamProxy.getGbId() > 0) {
                gbChannelService.update(streamProxy.buildCommonGBChannel());
            } else {
                gbChannelService.add(streamProxy.buildCommonGBChannel());
            }
        }
        return true;
    }

    @Override
    public PageInfo<StreamProxy> getAll(Integer page, Integer count, String query, Boolean pulling, String mediaServerId) {
        PageHelper.startPage(page, count);
        if (query != null) {
            query = query.replaceAll("/", "//")
                    .replaceAll("%", "/%")
                    .replaceAll("_", "/_");
        }
        List<StreamProxy> all = streamProxyMapper.selectAll(query, pulling, mediaServerId);
        return new PageInfo<>(all);
    }


    @Override
    public void startByAppAndStream(String app, String stream, ErrorCallback<StreamInfo> callback) {
        StreamProxy streamProxy = streamProxyMapper.selectOneByAppAndStream(app, stream);
        if (streamProxy == null) {
            throw new ControllerException(ErrorCode.ERROR404.getCode(), "代理信息未找到");
        }
        playService.startProxy(streamProxy, callback);
    }

    @Override
    public void stopByAppAndStream(String app, String stream) {
        StreamProxy streamProxy = streamProxyMapper.selectOneByAppAndStream(app, stream);
        if (streamProxy == null) {
            throw new ControllerException(ErrorCode.ERROR404.getCode(), "代理信息未找到");
        }
        playService.stopProxy(streamProxy);
    }


    @Override
    public Map<String, String> getFFmpegCMDs(MediaServer mediaServer) {
        return mediaServerService.getFFmpegCMDs(mediaServer);
    }


    @Override
    public StreamProxy getStreamProxyByAppAndStream(String app, String stream) {
        return streamProxyMapper.selectOneByAppAndStream(app, stream);
    }

    @Override
    @Transactional
    public void zlmServerOnline(MediaServer mediaServer) {
        if (mediaServer == null) {
            return;
        }
        // 这里主要是控制数据库/redis缓存/以及zlm中存在的代理流 三者状态一致。以数据库中数据为根本
        redisCatchStorage.removeStream(mediaServer.getId(), "PULL");

        List<StreamProxy> streamProxies = streamProxyMapper.selectForPushingInMediaServer(mediaServer.getId(), true);
        if (streamProxies.isEmpty()) {
            return;
        }
        Map<String, StreamProxy> streamProxyMapForDb = new HashMap<>();
        for (StreamProxy streamProxy : streamProxies) {
            streamProxyMapForDb.put(buildAppStreamKey(streamProxy.getApp(), streamProxy.getStream()), streamProxy);
        }

        List<StreamInfo> streamInfoList = mediaServerService.getMediaList(mediaServer, null, null, null);

        List<CommonGBChannel> channelListForOnline = new ArrayList<>();
        for (StreamInfo streamInfo : streamInfoList) {
            String key = buildAppStreamKey(streamInfo.getApp(), streamInfo.getStream());
            StreamProxy streamProxy = streamProxyMapForDb.get(key);
            if (streamProxy == null) {
                // 流媒体存在，数据库中不存在
                continue;
            }
            if (streamInfo.getOriginType() == OriginType.PULL.ordinal()
                    || streamInfo.getOriginType() == OriginType.FFMPEG_PULL.ordinal()) {
                if (streamProxyMapForDb.get(key) != null) {
                    redisCatchStorage.addStream(mediaServer, "pull", streamInfo.getApp(), streamInfo.getStream(), streamInfo.getMediaInfo());
                    if ("OFF".equalsIgnoreCase(streamProxy.getGbStatus()) && streamProxy.getGbId() > 0) {
                        streamProxy.setGbStatus("ON");
                        channelListForOnline.add(streamProxy.buildCommonGBChannel());
                    }
                    streamProxyMapForDb.remove(key);
                }
            }
        }

        if (!channelListForOnline.isEmpty()) {
            gbChannelService.online(channelListForOnline, true);
        }
        List<CommonGBChannel> channelListForOffline = new ArrayList<>();
        List<StreamProxy> streamProxiesForRemove = new ArrayList<>();
        if (!streamProxyMapForDb.isEmpty()) {
            for (StreamProxy streamProxy : streamProxyMapForDb.values()) {
                if ("ON".equalsIgnoreCase(streamProxy.getGbStatus()) && streamProxy.getGbId() > 0) {
                    streamProxy.setGbStatus("OFF");
                    channelListForOffline.add(streamProxy.buildCommonGBChannel());
                }
            }
        }
        if (!channelListForOffline.isEmpty()) {
            gbChannelService.offline(channelListForOffline, true);
        }
        if (!streamProxiesForRemove.isEmpty()) {
            streamProxyMapper.deleteByList(streamProxiesForRemove);
        }

        if (!streamProxyMapForDb.isEmpty()) {
            for (StreamProxy streamProxy : streamProxyMapForDb.values()) {
                streamProxyMapper.offline(streamProxy.getId());
            }
        }
    }

    @Override
    public void zlmServerOffline(MediaServer mediaServer) {
        List<StreamProxy> streamProxies = streamProxyMapper.selectForPushingInMediaServer(mediaServer.getId(), true);

        // 清理redis相关的缓存
        redisCatchStorage.removeStream(mediaServer.getId(), "PULL");

        if (streamProxies.isEmpty()) {
            return;
        }
        List<StreamProxy> streamProxiesForSendMessage = new ArrayList<>();
        List<CommonGBChannel> channelListForOffline = new ArrayList<>();

        for (StreamProxy streamProxy : streamProxies) {
            if (streamProxy.getGbId() > 0 && "ON".equalsIgnoreCase(streamProxy.getGbStatus())) {
                channelListForOffline.add(streamProxy.buildCommonGBChannel());
            }
            if ("ON".equalsIgnoreCase(streamProxy.getGbStatus())) {
                streamProxiesForSendMessage.add(streamProxy);
            }
        }
        if (!channelListForOffline.isEmpty()) {
            // 修改国标关联的国标通道的状态
            gbChannelService.offline(channelListForOffline, true);
        }
        if (!streamProxiesForSendMessage.isEmpty()) {
            for (StreamProxy streamProxy : streamProxiesForSendMessage) {
                JSONObject jsonObject = new JSONObject();
                jsonObject.put("serverId", userSetting.getServerId());
                jsonObject.put("app", streamProxy.getApp());
                jsonObject.put("stream", streamProxy.getStream());
                jsonObject.put("register", false);
                jsonObject.put("mediaServerId", mediaServer);
                redisCatchStorage.sendStreamChangeMsg("pull", jsonObject);
            }
        }
    }

    private String buildAppStreamKey(String app, String stream) {
        return app + "_" + stream;
    }

    @Transactional
    public void streamChangeHandler(String app, String stream, String mediaServerId, boolean status) {
        StreamProxy streamProxy = streamProxyMapper.selectOneByAppAndStream(app, stream);
        if (streamProxy == null) {
            return;
        }
        streamProxy.setPulling(status);
        streamProxy.setMediaServerId(mediaServerId);
        streamProxy.setUpdateTime(DateUtil.getNow());
        streamProxyMapper.updateStream(streamProxy);
        syncGbChannelByPulling(streamProxy, status);
    }

    /**
     * 拉流状态变化时同步绑定的国标通道，online/offline 会发 Catalog ON/OFF 给已订阅的上级。
     */
    private void syncGbChannelByPulling(StreamProxy streamProxy, boolean pulling) {
        if (streamProxy.getGbId() <= 0) {
            return;
        }
        CommonGBChannel channel = streamProxy.buildCommonGBChannel();
        if (channel == null) {
            return;
        }
        boolean gbOnline = "ON".equalsIgnoreCase(streamProxy.getGbStatus());
        if (pulling && !gbOnline) {
            log.info("[拉流代理] 通道上线 {}/{} -> {}", streamProxy.getApp(), streamProxy.getStream(), streamProxy.getGbDeviceId());
            gbChannelService.online(channel);
        } else if (!pulling && gbOnline) {
            log.info("[拉流代理] 通道离线 {}/{} -> {}", streamProxy.getApp(), streamProxy.getStream(), streamProxy.getGbDeviceId());
            gbChannelService.offline(channel);
        }
    }

    @Scheduled(fixedDelay = 10, initialDelay = 45, timeUnit = TimeUnit.SECONDS)
    public void scheduledCheckIdleStreamProxies() {
        int interval = userSetting.getStreamProxyIdleCheckInterval();
        if (interval <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastIdleCheckTime < interval * 1000L) {
            return;
        }
        if (!idleCheckRunning.compareAndSet(false, true)) {
            return;
        }
        lastIdleCheckTime = now;
        try {
            checkIdleStreamProxies();
        } finally {
            idleCheckRunning.set(false);
        }
    }

    @Override
    public void checkIdleStreamProxies() {
        List<StreamProxy> proxies = streamProxyMapper.selectEnabledByServerId(userSetting.getServerId());
        if (proxies == null || proxies.isEmpty()) {
            return;
        }
        List<MediaServer> onlineServers = mediaServerService.getAllOnline();
        if (onlineServers == null || onlineServers.isEmpty()) {
            return;
        }
        for (StreamProxy streamProxy : proxies) {
            try {
                handleIdleStreamProxy(streamProxy, onlineServers);
            } catch (Exception e) {
                log.warn("[拉流代理-空闲检测] 处理失败 {}/{}: {}", streamProxy.getApp(), streamProxy.getStream(), e.getMessage());
            }
        }
    }

    private void handleIdleStreamProxy(StreamProxy streamProxy, List<MediaServer> onlineServers) {
        boolean pulling = Boolean.TRUE.equals(streamProxy.getPulling());
        String readyMediaServerId = findReadyMediaServerId(streamProxy, onlineServers);
        boolean gbOnline = streamProxy.getGbId() > 0 && "ON".equalsIgnoreCase(streamProxy.getGbStatus());

        // 已在拉流的代理不由定时任务停流/下线/重拉，避免查询抖动误伤正在共享的通道
        if (pulling) {
            if (readyMediaServerId != null && !gbOnline && streamProxy.getGbId() > 0) {
                log.info("[拉流代理-空闲检测] 正在拉流但通道未在线，补发上线 {}/{}", streamProxy.getApp(), streamProxy.getStream());
                streamChangeHandler(streamProxy.getApp(), streamProxy.getStream(), readyMediaServerId, true);
            }
            return;
        }

        if (readyMediaServerId != null) {
            log.info("[拉流代理-空闲检测] 尚未拉流但媒体节点已有流，同步状态 {}/{}", streamProxy.getApp(), streamProxy.getStream());
            streamChangeHandler(streamProxy.getApp(), streamProxy.getStream(), readyMediaServerId, true);
            return;
        }
        if (streamProxy.isEnableDisableNoneReader()) {
            return;
        }
        retryStartIdleProxy(streamProxy);
    }

    private void retryStartIdleProxy(StreamProxy streamProxy) {
        int proxyId = streamProxy.getId();
        if (!idleCheckInProgress.add(proxyId)) {
            return;
        }
        StreamProxy toStart = streamProxy;
        if (!ObjectUtils.isEmpty(streamProxy.getStreamKey()) || !ObjectUtils.isEmpty(streamProxy.getMediaServerId())) {
            try {
                playService.stopProxy(streamProxy);
            } catch (Exception e) {
                log.debug("[拉流代理-空闲检测] 清理旧代理失败 {}/{}: {}", streamProxy.getApp(), streamProxy.getStream(), e.getMessage());
            }
            toStart = streamProxyMapper.select(proxyId);
            if (toStart == null || !toStart.isEnable()) {
                idleCheckInProgress.remove(proxyId);
                return;
            }
        }
        final StreamProxy target = toStart;
        log.info("[拉流代理-空闲检测] 尝试拉流 {}/{}，源：{}", target.getApp(), target.getStream(), target.getSrcUrl());
        try {
            playService.startProxy(target, (code, msg, data) -> {
                idleCheckInProgress.remove(proxyId);
                if (code == ErrorCode.SUCCESS.getCode() || code == InviteErrorCode.SUCCESS.getCode()) {
                    log.info("[拉流代理-空闲检测] 拉流成功 {}/{}", target.getApp(), target.getStream());
                    String mediaServerId = target.getMediaServerId();
                    if (data != null && data.getMediaServer() != null) {
                        mediaServerId = data.getMediaServer().getId();
                    }
                    streamChangeHandler(target.getApp(), target.getStream(), mediaServerId, true);
                } else {
                    log.info("[拉流代理-空闲检测] 源暂无流 {}/{}: {}", target.getApp(), target.getStream(), msg);
                }
            });
        } catch (Exception e) {
            idleCheckInProgress.remove(proxyId);
            log.info("[拉流代理-空闲检测] 启动失败 {}/{}: {}", target.getApp(), target.getStream(), e.getMessage());
        }
    }

    private String findReadyMediaServerId(StreamProxy streamProxy, List<MediaServer> onlineServers) {
        List<MediaServer> candidates = new ArrayList<>();
        addMediaServerIfPresent(candidates, streamProxy.getMediaServerId());
        addMediaServerIfPresent(candidates, streamProxy.getRelatesMediaServerId());
        if (candidates.isEmpty()) {
            candidates.addAll(onlineServers);
        }
        for (MediaServer mediaServer : candidates) {
            try {
                if (Boolean.TRUE.equals(mediaServerService.isStreamReady(mediaServer, streamProxy.getApp(), streamProxy.getStream()))) {
                    return mediaServer.getId();
                }
            } catch (Exception e) {
                log.debug("[拉流代理-空闲检测] 查询流失败 {} {}/{}: {}", mediaServer.getId(), streamProxy.getApp(), streamProxy.getStream(), e.getMessage());
            }
        }
        return null;
    }

    private void addMediaServerIfPresent(List<MediaServer> candidates, String mediaServerId) {
        if (ObjectUtils.isEmpty(mediaServerId)) {
            return;
        }
        for (MediaServer exist : candidates) {
            if (mediaServerId.equals(exist.getId())) {
                return;
            }
        }
        MediaServer mediaServer = mediaServerService.getOne(mediaServerId);
        if (mediaServer != null) {
            candidates.add(mediaServer);
        }
    }

    @Override
    public ResourceBaseInfo getOverview() {

        int total = streamProxyMapper.getAllCount();
        int online = streamProxyMapper.getOnline();

        return new ResourceBaseInfo(total, online);
    }

    @Override
    public StreamProxy getStreamProxy(int id) {
        return streamProxyMapper.select(id);
    }

}
