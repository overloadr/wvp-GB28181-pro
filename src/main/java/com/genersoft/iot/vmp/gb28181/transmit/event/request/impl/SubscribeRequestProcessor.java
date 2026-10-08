package com.genersoft.iot.vmp.gb28181.transmit.event.request.impl;

import com.genersoft.iot.vmp.gb28181.bean.*;
import com.genersoft.iot.vmp.gb28181.service.IPlatformService;
import com.genersoft.iot.vmp.gb28181.transmit.SIPProcessorObserver;
import com.genersoft.iot.vmp.gb28181.transmit.SIPSender;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.ISIPRequestProcessor;
import com.genersoft.iot.vmp.gb28181.transmit.event.request.SIPRequestProcessorParent;
import com.genersoft.iot.vmp.gb28181.utils.SipUtils;
import com.genersoft.iot.vmp.gb28181.utils.XmlUtil;
import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.message.SIPResponse;
import lombok.extern.slf4j.Slf4j;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sip.InvalidArgumentException;
import javax.sip.RequestEvent;
import javax.sip.SipException;
import javax.sip.header.EventHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.message.Response;
import java.text.ParseException;

/**
 * SIP命令类型： SUBSCRIBE请求
 * @author lin
 */
@Slf4j
@Component
public class SubscribeRequestProcessor extends SIPRequestProcessorParent implements InitializingBean, ISIPRequestProcessor {

	private final String method = "SUBSCRIBE";

	/** 续订未带 Expires、且本地已无有效订阅时使用的默认有效期（秒） */
	private static final int DEFAULT_MOBILE_POSITION_EXPIRES = 3600;

	@Autowired
	private SIPProcessorObserver sipProcessorObserver;

	@Autowired
	private SubscribeHolder subscribeHolder;

	@Autowired
	private SIPSender sipSender;


	@Autowired
	private IPlatformService platformService;

	@Override
	public void afterPropertiesSet() throws Exception {
		// 添加消息处理的订阅
		sipProcessorObserver.addRequestProcessor(method, this);
	}

	/**
	 * 处理SUBSCRIBE请求
	 *
	 * @param evt 事件
	 */
	@Override
	public void process(RequestEvent evt) {
		SIPRequest request = (SIPRequest) evt.getRequest();
		try {
			Element rootElement = null;
			try {
				rootElement = getRootElement(evt);
			} catch (DocumentException e) {
				log.warn("[收到订阅请求] 消息体无法解析，按无消息体续订处理: {}", e.getMessage());
			}
			String platformId = SipUtils.getUserIdFromFromHeader(request);
			String cmd = rootElement == null ? null : XmlUtil.getText(rootElement, "CmdType");
			EventHeader eventHeader = (EventHeader) request.getHeader(EventHeader.NAME);
			log.info("[收到订阅请求] 类型： {}, 来自： {}", cmd, platformId);
			if (isMobilePositionSubscribe(platformId, cmd, eventHeader)) {
				processNotifyMobilePosition(request, rootElement, eventHeader);
				return;
			}
			if (rootElement == null) {
				log.error("处理SUBSCRIBE请求  未获取到消息体{}", evt.getRequest());
				responseAck(request, Response.BAD_REQUEST);
				return;
			}
			ExpiresHeader expires = request.getExpires();
			if (expires == null) {
				log.error("处理SUBSCRIBE请求  未获取到ExpiresHeader{}", evt.getRequest());
				responseAck(request, Response.BAD_REQUEST, "missing expires");
				return;
			}
			if (CmdType.CATALOG.equals(cmd)) {
				processNotifyCatalogList(request, rootElement);
			} else {
                log.info("接收到消息：{}", cmd);

				Response response = getMessageFactory().createResponse(200, request);
				if (response != null) {
					ExpiresHeader expireHeader = getHeaderFactory().createExpiresHeader(30);
					response.setExpires(expireHeader);
					Platform platform = platformService.queryPlatformByServerGBId(platformId);
					if (platform != null) {
						addSubscribeContactHeader(response, platform);
					}
				}
                log.info("response : {}", response);
				sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
			}
		} catch (ParseException | SipException | InvalidArgumentException e) {
			log.error("未处理的异常 ", e);
		}

	}

	/**
	 * 首次订阅、有效期内刷新、到期后续订都回 200，并刷新本地订阅。
	 * 兼容：无消息体、消息体损坏、未带 Expires、未带 Event、Interval 非法。
	 * Expires=0 仍表示取消订阅。
	 */
	private void processNotifyMobilePosition(SIPRequest request, Element rootElement, EventHeader eventHeader) throws SipException {
		if (request == null) {
			return;
		}
		String platformId = SipUtils.getUserIdFromFromHeader(request);
		Platform platform = platformService.queryPlatformByServerGBId(platformId);
		SubscribeInfo previous = subscribeHolder.getMobilePositionSubscribe(platformId);
		int expires = resolveMobilePositionExpires(request, previous, platformId);
		String sn = textOrDefault(rootElement, "SN", previous != null ? previous.getSn() : "1");
		String deviceId = textOrDefault(rootElement, "DeviceID", platform != null ? platform.getDeviceGBId() : platformId);
		log.info("[回复上级的移动位置订阅请求]: {}，expires={}s，续订={}", platformId, expires, previous != null);
		StringBuilder resultXml = new StringBuilder(200);
		resultXml.append("<?xml version=\"1.0\" ?>\r\n")
				.append("<Response>\r\n")
				.append("<CmdType>MobilePosition</CmdType>\r\n")
				.append("<SN>").append(sn).append("</SN>\r\n")
				.append("<DeviceID>").append(deviceId).append("</DeviceID>\r\n")
				.append("<Result>OK</Result>\r\n")
				.append("</Response>\r\n");
		try {
			ensureMobilePositionEvent(request, eventHeader, previous);
			SIPResponse response;
			if (platform != null) {
				response = responseXmlAck(request, resultXml.toString(), platform, expires);
			} else {
				log.warn("[移动位置订阅] 未找到平台 {}，仍回复 200 以结束本次事务", platformId);
				response = responseSubscribeOk(request, resultXml.toString(), expires);
			}

			if (expires == 0) {
				subscribeHolder.removeMobilePositionSubscribe(platformId);
				return;
			}
			if (platform == null) {
				return;
			}
			EventHeader responseEvent = (EventHeader) request.getHeader(EventHeader.NAME);
			SubscribeInfo subscribeInfo = SubscribeInfo.getInstance(response, platformId, expires, responseEvent);
			if (subscribeInfo.getEventType() == null || subscribeInfo.getEventType().isBlank()) {
				subscribeInfo.setEventType(previous != null && previous.getEventType() != null
						? previous.getEventType() : CmdType.MOBILE_POSITION);
			}
			if (subscribeInfo.getEventId() == null && previous != null) {
				subscribeInfo.setEventId(previous.getEventId());
			}
			subscribeInfo.setGpsInterval(resolveGpsInterval(rootElement, previous));
			subscribeInfo.setSn(sn);
			subscribeInfo.setTransactionInfo(new SipTransactionInfo(response));
			subscribeHolder.putMobilePositionSubscribe(platformId, subscribeInfo, () -> {
				platformService.sendNotifyMobilePosition(platformId);
			});
		} catch (SipException | InvalidArgumentException | ParseException e) {
			log.error("未处理的异常 ", e);
		}
	}

	private boolean isMobilePositionSubscribe(String platformId, String cmd, EventHeader eventHeader) {
		if (CmdType.MOBILE_POSITION.equals(cmd)) {
			return true;
		}
		if (eventHeader != null && CmdType.MOBILE_POSITION.equalsIgnoreCase(eventHeader.getEventType())) {
			return true;
		}
		SubscribeInfo current = subscribeHolder.getMobilePositionSubscribe(platformId);
		if (current == null || eventHeader == null || eventHeader.getEventType() == null) {
			return false;
		}
		if (current.getEventType() != null && !current.getEventType().equalsIgnoreCase(eventHeader.getEventType())) {
			return false;
		}
		if (current.getEventId() != null && eventHeader.getEventId() != null
				&& !current.getEventId().equals(eventHeader.getEventId())) {
			return false;
		}
		return true;
	}

	private int resolveMobilePositionExpires(SIPRequest request, SubscribeInfo previous, String platformId) {
		if (request.getExpires() != null) {
			return Math.max(request.getExpires().getExpires(), 0);
		}
		if (previous != null && previous.getExpires() > 0) {
			log.info("[移动位置订阅] 未携带Expires，沿用已有有效期 {}s，平台 {}", previous.getExpires(), platformId);
			return previous.getExpires();
		}
		log.info("[移动位置订阅] 未携带Expires且本地订阅已失效，按 {}s 接受，平台 {}", DEFAULT_MOBILE_POSITION_EXPIRES, platformId);
		return DEFAULT_MOBILE_POSITION_EXPIRES;
	}

	private int resolveGpsInterval(Element rootElement, SubscribeInfo previous) {
		String interval = rootElement == null ? null : XmlUtil.getText(rootElement, "Interval");
		if (interval != null) {
			try {
				int value = Integer.parseInt(interval.trim());
				if (value > 0) {
					return value;
				}
			} catch (NumberFormatException ignored) {
				log.warn("[移动位置订阅] Interval 无法解析: {}", interval);
			}
		}
		if (previous != null && previous.getGpsInterval() > 0) {
			return previous.getGpsInterval();
		}
		return 5;
	}

	private void ensureMobilePositionEvent(SIPRequest request, EventHeader eventHeader, SubscribeInfo previous) throws ParseException {
		if (request.getHeader(EventHeader.NAME) != null) {
			return;
		}
		String eventType = CmdType.MOBILE_POSITION;
		String eventId = null;
		if (eventHeader != null && eventHeader.getEventType() != null) {
			eventType = eventHeader.getEventType();
			eventId = eventHeader.getEventId();
		} else if (previous != null && previous.getEventType() != null) {
			eventType = previous.getEventType();
			eventId = previous.getEventId();
		}
		EventHeader event = getHeaderFactory().createEventHeader(eventType);
		if (eventId != null) {
			event.setEventId(eventId);
		}
		request.addHeader(event);
	}

	private String textOrDefault(Element rootElement, String name, String defaultValue) {
		if (rootElement == null) {
			return defaultValue == null ? "" : defaultValue;
		}
		String text = XmlUtil.getText(rootElement, name);
		if (text == null || text.isBlank()) {
			return defaultValue == null ? "" : defaultValue;
		}
		return text;
	}

	private void processNotifyAlarm(RequestEvent evt, Element rootElement) {

	}

	private void processNotifyCatalogList(SIPRequest request, Element rootElement) throws SipException {
		if (request == null) {
			log.info("[处理目录订阅] 发现request为NUll。已忽略");
			return;
		}
		String platformId = SipUtils.getUserIdFromFromHeader(request);
		String deviceId = XmlUtil.getText(rootElement, "DeviceID");
		Platform platform = platformService.queryPlatformByServerGBId(platformId);
		if (platform == null){
			log.info("[处理目录订阅] 未找到平台 {}。已忽略", platformId);
			return;
		}

		String sn = XmlUtil.getText(rootElement, "SN");
		log.info("[回复上级的目录订阅请求]: {}/{}", platformId, deviceId);
		StringBuilder resultXml = new StringBuilder(200);
		resultXml.append("<?xml version=\"1.0\" ?>\r\n")
				.append("<Response>\r\n")
				.append("<CmdType>Catalog</CmdType>\r\n")
				.append("<SN>").append(sn).append("</SN>\r\n")
				.append("<DeviceID>").append(deviceId).append("</DeviceID>\r\n")
				.append("<Result>OK</Result>\r\n")
				.append("</Response>\r\n");

		try {
			int expires = request.getExpires().getExpires();
			Platform parentPlatform = platformService.queryPlatformByServerGBId(platformId);
			SIPResponse response = responseXmlAck(request, resultXml.toString(), parentPlatform, expires);

			SubscribeInfo subscribeInfo = SubscribeInfo.getInstance(response, platformId, expires,
					(EventHeader)request.getHeader(EventHeader.NAME));

			if (subscribeInfo.getExpires() == 0) {
				subscribeHolder.removeCatalogSubscribe(platformId);
			}else {
				subscribeInfo.setTransactionInfo(new SipTransactionInfo(response));
				subscribeHolder.putCatalogSubscribe(platformId, subscribeInfo);
			}
		} catch (SipException | InvalidArgumentException | ParseException e) {
			log.error("未处理的异常 ", e);
		}
		if (subscribeHolder.getCatalogSubscribe(platformId) == null
				&& platform.getAutoPushChannel() != null && platform.getAutoPushChannel()) {
			platformService.addSimulatedSubscribeInfo(platform);
		}
	}
}
