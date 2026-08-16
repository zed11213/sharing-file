package jp.co.jsbank.mobile.bff.service.impl;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.annotation.Resource;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jp.co.jsbank.mobile.bff.common.Constant;
import jp.co.jsbank.mobile.bff.common.ErrorCodeConstant;
import jp.co.jsbank.mobile.bff.common.GetIdByTokenHandler;
import jp.co.jsbank.mobile.bff.common.MessageIdConstant;
import jp.co.jsbank.mobile.bff.common.RequestHeaderContext;
import jp.co.jsbank.mobile.bff.common.code.LoginFlgCode;
import jp.co.jsbank.mobile.bff.common.code.TokenFlgCode;
import jp.co.jsbank.mobile.bff.common.code.UserStatusCode;
import jp.co.jsbank.mobile.bff.common.code.UtilizationStatusCode;
import jp.co.jsbank.mobile.bff.common.exception.BadRequestException;
import jp.co.jsbank.mobile.bff.common.exception.InternalServerErrorException;
import jp.co.jsbank.mobile.bff.common.util.NullPropertyNamesUtils;
import jp.co.jsbank.mobile.bff.common.util.Utils;
import jp.co.jsbank.mobile.bff.dto.LoginHistoryDetailsDto;
import jp.co.jsbank.mobile.bff.dto.LoginHistoryResponseDto;
import jp.co.jsbank.mobile.bff.dto.LoginRequestDto;
import jp.co.jsbank.mobile.bff.dto.aba.LoginHistoryAbaResponseDto;
import jp.co.jsbank.mobile.bff.dto.aba.RegistloginHistoryAbaRequestDto;
import jp.co.jsbank.mobile.bff.dto.aba.UserAttributeInfoDto;
import jp.co.jsbank.mobile.bff.dto.aba.UserAttributeInquiryAbaRequestDto;
import jp.co.jsbank.mobile.bff.dto.aba.UserAttributeInquiryAbaResponseDto;
import jp.co.jsbank.mobile.bff.dto.aba.UserAttributeUpdateAbaRequestDto;
import jp.co.jsbank.mobile.bff.logic.LoginLogic;
import jp.co.jsbank.mobile.bff.logic.SignUpLogic;
import jp.co.jsbank.mobile.bff.service.LoginService;

/**
 * ログインサービス実装クラス
 */
@Service
public class LoginServiceImpl implements LoginService {
	/**
	 * ログ出力のためのクラス
	 */
	private static final Logger logger = LoggerFactory.getLogger(LoginServiceImpl.class);

	@Resource
	private LoginLogic loginLogic;

	@Resource
	private SignUpLogic signUpLogic;

	@Autowired
	private GetIdByTokenHandler getIdByTokenHandler;

	/**
	 * ログイン認証
	 *
	 * @return 処理結果
	 */
	@Override
	public LoginHistoryResponseDto loginAuthentication(LoginRequestDto requestDto) {

		String userIDToken = RequestHeaderContext.getInstance().getUserIdToken();
		// 利用者IDを取得する
		String userId = getIdByTokenHandler.getIdByToken(userIDToken);

		// 顧客ID
		String customerIdToken = RequestHeaderContext.getInstance().getJbaIdToken();
		String customerId = getIdByTokenHandler.getIdByToken(customerIdToken);

		//トーケン発行フラグ
		String tokenFlg = TokenFlgCode.MI;

		//初回ログインの場合、「利用中」にする。
		if (LoginFlgCode.MI.equals(requestDto.getLoginFlg())) {

			// 利用者属性照会ABAリクエストDTO
			UserAttributeInquiryAbaRequestDto userAbaRequestDto = new UserAttributeInquiryAbaRequestDto();
			userAbaRequestDto.setUserId(userId);

			// エンドユーザーログインを呼び出す（API ID：AA0209）
			UserAttributeInquiryAbaResponseDto abaResponseDto = loginLogic.userAttributeInquiry(userAbaRequestDto);

			// 利用者属性情報DTOリスト
			List<UserAttributeInfoDto> userInfoList = abaResponseDto.getUserInfoList();
			if (Objects.nonNull(userInfoList) && userInfoList.size() > 0) {

				// 利用者属性情報DTO
				UserAttributeInfoDto userAttributeInfoDto = userInfoList.get(0);

				// 利用者属性更新ABAリクエストDTO
				UserAttributeUpdateAbaRequestDto userAttributeUpdateAbaRequestDto = new UserAttributeUpdateAbaRequestDto();
				BeanUtils.copyProperties(userAttributeInfoDto, userAttributeUpdateAbaRequestDto);
				NullPropertyNamesUtils.setNullPropertyNames(userAttributeUpdateAbaRequestDto);
				// OS
				userAttributeUpdateAbaRequestDto.setOperatingSystem(userAttributeInfoDto.getOs());
				// 最新更新日時
				userAttributeUpdateAbaRequestDto.setUpdateDatetime(userAttributeInfoDto.getUpdateTs());
				userAttributeUpdateAbaRequestDto.setStatus(UserStatusCode.NORMAL);
				userAttributeUpdateAbaRequestDto.setUtilizationStatus(UtilizationStatusCode.USING);
				// 利用者属性更新
				signUpLogic.userAttributeUpdate(userAttributeUpdateAbaRequestDto, userAttributeInfoDto.getDeviceId());
			}
			tokenFlg = TokenFlgCode.ZUMI;
		}

		// ログイン履歴登録
		RegistloginHistoryAbaRequestDto registloginHistoryAbaRequestDto = new RegistloginHistoryAbaRequestDto();

		registloginHistoryAbaRequestDto.setCustomerId(customerId);

		// 利用者ID
		registloginHistoryAbaRequestDto.setUserId(userId);
		// ログイン履歴登録呼出
		signUpLogic.registLoginHistory(registloginHistoryAbaRequestDto);
		String loginTs = "なし";

		try {
			LoginHistoryAbaResponseDto loginHistoryAbaResponseDto = signUpLogic
					.loginHistoryInquiry(registloginHistoryAbaRequestDto);

			List<LoginHistoryDetailsDto> loginHistoryDetailsList = loginHistoryAbaResponseDto.getLoginHistoryList();
			if (Objects.nonNull(loginHistoryDetailsList) && loginHistoryDetailsList.size() > 1) {
				loginTs = loginHistoryDetailsList.get(1).getLoginTs();
				try {
					loginTs = Utils.changeTImeFromABATimestmapString(loginTs);
				} catch (Exception e) {
					for (StackTraceElement elm : e.getStackTrace())
						logger.info(Constant.STRDEF_AT + elm.toString());
					Optional.ofNullable(e.getCause()).ifPresent(cause -> {
						logger.info(Constant.STRDEF_CAUSED_BY + cause.toString());
						Arrays.asList(cause.getStackTrace()).stream()
								.forEach(ste -> logger.info(Constant.STRDEF_AT + ste.toString()));
					});
					throw new InternalServerErrorException(MessageIdConstant.MESSAGE_ID_MBAP0009, "時間変換失敗");
				}
			}

		} catch (BadRequestException e) {
			if (!ErrorCodeConstant.ERROR_CODE_71200.equals(e.getCode())) {
				throw e;
			}
		}
		LoginHistoryResponseDto responseDto = new LoginHistoryResponseDto();
		responseDto.setLoginTs(loginTs);
		responseDto.setTokenFlg(tokenFlg);
		return responseDto;
	}
}
