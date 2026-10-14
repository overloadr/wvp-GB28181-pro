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
import javax.sip.address.SipURI;
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
			Element rootElement = getRootElement(evt);
			if (rootElement == null) {
				// 科达等上级刷新订阅时常不带 MANSCDP 消息体（Content-Length: 0），
				// RFC 3265 允许刷新 SUBSCRIBE 无消息体，不能直接回 400，否则订阅对话中断，上级通道状态会掉线。
				processSubscribeWithoutBody(request);
				return;
			}
			ExpiresHeader expires = request.getExpires();
			if (expires == null) {
				log.error("处理SUBSCRIBE请求  未获取到ExpiresHeader{}", evt.getRequest());
				responseAck(request, Response.BAD_REQUEST, "missing expires");
				return;
			}
			String platformId = SipUtils.getUserIdFromFromHeader(request);
			String cmd = XmlUtil.getText(rootElement, "CmdType");
			log.info("[收到订阅请求] 类型： {}, 来自： {}", cmd, platformId);
			if (CmdType.MOBILE_POSITION.equals(cmd)) {
				processNotifyMobilePosition(request, rootElement);
//			} else if (CmdType.ALARM.equals(cmd)) {
//				logger.info("接收到Alarm订阅");
//				processNotifyAlarm(serverTransaction, rootElement);
			} else if (CmdType.CATALOG.equals(cmd)) {
				processNotifyCatalogList(request, rootElement);
			} else {
                log.info("接收到消息：{}", cmd);

				Response response = getMessageFactory().createResponse(200, request);
				if (response != null) {
					ExpiresHeader expireHeader = getHeaderFactory().createExpiresHeader(30);
					response.setExpires(expireHeader);
					Platform platform = platformService.queryPlatformByServerGBId(platformId);
					response.addHeader(getHeaderFactory().createContactHeader(
							createContactAddress(createLocalContactSipURI(request, platform))));
				}
                log.info("response : {}", response);
				sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
			}
		} catch (ParseException | SipException | InvalidArgumentException | DocumentException e) {
			log.error("未处理的异常 ", e);
		}

	}

	/**
	 * 无消息体的 SUBSCRIBE：按 Event 头续订或新建订阅。
	 * 同一 Call-ID 的刷新只回 200 并延长有效期，不再重推离线通道，避免上级把共享通道重新判离线。
	 */
	private void processSubscribeWithoutBody(SIPRequest request) throws SipException, InvalidArgumentException, ParseException {
		ExpiresHeader expiresHeader = request.getExpires();
		if (expiresHeader == null) {
			log.error("处理SUBSCRIBE请求  未获取到ExpiresHeader{}", request);
			responseAck(request, Response.BAD_REQUEST, "missing expires");
			return;
		}
		EventHeader eventHeader = (EventHeader) request.getHeader(EventHeader.NAME);
		String eventType = eventHeader == null ? null : eventHeader.getEventType();
		String platformId = SipUtils.getUserIdFromFromHeader(request);
		log.info("[收到无消息体订阅] 类型： {}，来自： {}，Expires： {}", eventType, platformId, expiresHeader.getExpires());
		if (eventType == null) {
			log.error("处理SUBSCRIBE请求  未获取到消息体且无Event头{}", request);
			responseAck(request, Response.BAD_REQUEST, "missing event");
			return;
		}
		if (CmdType.CATALOG.equalsIgnoreCase(eventType)) {
			processNotifyCatalogListWithoutBody(request, eventHeader);
		} else if (CmdType.MOBILE_POSITION.equalsIgnoreCase(eventType)) {
			processNotifyMobilePositionWithoutBody(request, eventHeader);
		} else {
			log.info("[无消息体订阅] 未识别的Event：{}，回复200", eventType);
			Platform platform = platformService.queryPlatformByServerGBId(platformId);
			Response response = getMessageFactory().createResponse(Response.OK, request);
			response.setExpires(getHeaderFactory().createExpiresHeader(expiresHeader.getExpires()));
			if (platform != null) {
				response.addHeader(getHeaderFactory().createContactHeader(
						createContactAddress(createLocalContactSipURI(request, platform))));
			}
			sipSender.transmitRequest(request.getLocalAddress().getHostAddress(), response);
		}
	}

	private void processNotifyCatalogListWithoutBody(SIPRequest request, EventHeader eventHeader) {
		String platformId = SipUtils.getUserIdFromFromHeader(request);
		Platform platform = platformService.queryPlatformByServerGBId(platformId);
		if (platform == null) {
			log.info("[处理目录订阅] 未找到平台 {}。已忽略", platformId);
			try {
				responseAck(request, Response.NOT_FOUND);
			} catch (SipException | InvalidArgumentException | ParseException e) {
				log.error("未处理的异常 ", e);
			}
			return;
		}
		String deviceId = resolveSubscribeDeviceId(request, platform);
		SubscribeInfo oldSubscribe = subscribeHolder.getCatalogSubscribe(platformId);
		boolean sameDialog = isSameDialog(oldSubscribe, request);
		String sn = oldSubscribe != null && oldSubscribe.getSn() != null
				? oldSubscribe.getSn()
				: String.valueOf((int) ((Math.random() * 9 + 1) * 100000));
		int expires = request.getExpires().getExpires();
		log.info("[回复上级的目录订阅请求][无消息体]: {}/{}，Expires：{}，{}", platformId, deviceId, expires,
				sameDialog ? "刷新" : "新建");
		try {
			SIPResponse response = responseXmlAck(request, buildSubscribeResponseXml(CmdType.CATALOG, sn, deviceId), platform, expires);
			if (expires == 0) {
				subscribeHolder.removeCatalogSubscribe(platformId);
				return;
			}
			SubscribeInfo subscribeInfo = SubscribeInfo.getInstance(response, platformId, expires, eventHeader);
			subscribeInfo.setSn(sn);
			subscribeInfo.setTransactionInfo(new SipTransactionInfo(response));
			subscribeHolder.putCatalogSubscribe(platformId, subscribeInfo);
			if (!sameDialog) {
				platformService.sendNotifyForCatalogSubscribe(platform, subscribeInfo);
			}
		} catch (SipException | InvalidArgumentException | ParseException e) {
			log.error("未处理的异常 ", e);
		}
	}

	private void processNotifyMobilePositionWithoutBody(SIPRequest request, EventHeader eventHeader) {
		String platformId = SipUtils.getUserIdFromFromHeader(request);
		Platform platform = platformService.queryPlatformByServerGBId(platformId);
		if (platform == null) {
			log.info("[处理移动位置订阅] 未找到平台 {}。已忽略", platformId);
			try {
				responseAck(request, Response.NOT_FOUND);
			} catch (SipException | InvalidArgumentException | ParseException e) {
				log.error("未处理的异常 ", e);
			}
			return;
		}
		String deviceId = resolveSubscribeDeviceId(request, platform);
		SubscribeInfo oldSubscribe = subscribeHolder.getMobilePositionSubscribe(platformId);
		String sn = oldSubscribe != null && oldSubscribe.getSn() != null
				? oldSubscribe.getSn()
				: String.valueOf((int) ((Math.random() * 9 + 1) * 100000));
		int interval = oldSubscribe != null && oldSubscribe.getGpsInterval() > 0 ? oldSubscribe.getGpsInterval() : 5;
		int expires = request.getExpires().getExpires();
		log.info("[回复上级的移动位置订阅请求][无消息体]: {}", platformId);
		try {
			SIPResponse response = responseXmlAck(request, buildSubscribeResponseXml(CmdType.MOBILE_POSITION, sn, deviceId), platform, expires);
			if (expires == 0) {
				subscribeHolder.removeMobilePositionSubscribe(platformId);
				return;
			}
			SubscribeInfo subscribeInfo = SubscribeInfo.getInstance(response, platformId, expires, eventHeader);
			subscribeInfo.setGpsInterval(interval);
			subscribeInfo.setSn(sn);
			subscribeInfo.setTransactionInfo(new SipTransactionInfo(response));
			subscribeHolder.putMobilePositionSubscribe(platformId, subscribeInfo, () -> platformService.sendNotifyMobilePosition(platformId));
		} catch (SipException | InvalidArgumentException | ParseException e) {
			log.error("未处理的异常 ", e);
		}
	}

	private boolean isSameDialog(SubscribeInfo subscribeInfo, SIPRequest request) {
		if (subscribeInfo == null || subscribeInfo.getTransactionInfo() == null
				|| subscribeInfo.getTransactionInfo().getCallId() == null
				|| request.getCallIdHeader() == null) {
			return false;
		}
		return subscribeInfo.getTransactionInfo().getCallId().equals(request.getCallIdHeader().getCallId());
	}

	private String resolveSubscribeDeviceId(SIPRequest request, Platform platform) {
		if (request.getRequestURI() instanceof SipURI sipURI && sipURI.getUser() != null && !sipURI.getUser().isEmpty()) {
			return sipURI.getUser();
		}
		return platform.getDeviceGBId();
	}

	private String buildSubscribeResponseXml(String cmdType, String sn, String deviceId) {
		return "<?xml version=\"1.0\" ?>\r\n"
				+ "<Response>\r\n"
				+ "<CmdType>" + cmdType + "</CmdType>\r\n"
				+ "<SN>" + sn + "</SN>\r\n"
				+ "<DeviceID>" + deviceId + "</DeviceID>\r\n"
				+ "<Result>OK</Result>\r\n"
				+ "</Response>\r\n";
	}

	/**
	 * 处理移动位置订阅消息
	 */
	private void processNotifyMobilePosition(SIPRequest request, Element rootElement) throws SipException {
		if (request == null) {
			return;
		}
		String platformId = SipUtils.getUserIdFromFromHeader(request);
		String deviceId = XmlUtil.getText(rootElement, "DeviceID");
		Platform platform = platformService.queryPlatformByServerGBId(platformId);
		if (platform == null) {
			return;
		}

		String sn = XmlUtil.getText(rootElement, "SN");
		log.info("[回复上级的移动位置订阅请求]: {}", platformId);
		StringBuilder resultXml = new StringBuilder(200);
		resultXml.append("<?xml version=\"1.0\" ?>\r\n")
				.append("<Response>\r\n")
				.append("<CmdType>MobilePosition</CmdType>\r\n")
				.append("<SN>").append(sn).append("</SN>\r\n")
				.append("<DeviceID>").append(deviceId).append("</DeviceID>\r\n")
				.append("<Result>OK</Result>\r\n")
				.append("</Response>\r\n");
		try {
			int expires = request.getExpires().getExpires();
			SIPResponse response = responseXmlAck(request, resultXml.toString(), platform, expires);

			SubscribeInfo subscribeInfo = SubscribeInfo.getInstance(response, platformId, expires,
					(EventHeader)request.getHeader(EventHeader.NAME));
			if (subscribeInfo.getExpires() > 0) {
				// GPS上报时间间隔
				String interval = XmlUtil.getText(rootElement, "Interval");
				if (interval == null) {
					subscribeInfo.setGpsInterval(5);
				}else {
					subscribeInfo.setGpsInterval(Integer.parseInt(interval));
				}
				subscribeInfo.setSn(sn);
			}
			if (subscribeInfo.getExpires() == 0) {
				subscribeHolder.removeMobilePositionSubscribe(platformId);
			}else {
				subscribeInfo.setTransactionInfo(new SipTransactionInfo(response));
				subscribeHolder.putMobilePositionSubscribe(platformId, subscribeInfo, ()->{
					platformService.sendNotifyMobilePosition(platformId);
				});
			}

		} catch (SipException | InvalidArgumentException | ParseException e) {
			log.error("未处理的异常 ", e);
		}
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
				// RFC 3265 / GB28181 附录P：初始(及刷新)订阅 200 后立即 NOTIFY，激活上级订阅任务
				try {
					platformService.sendNotifyForCatalogSubscribe(parentPlatform, subscribeInfo);
				} catch (Exception e) {
					log.error("[目录订阅] 发送激活NOTIFY失败: {}", e.getMessage(), e);
				}
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
