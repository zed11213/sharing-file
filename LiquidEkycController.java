package jp.co.jsbank.mobile.bff.controller;

import javax.annotation.Resource;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import org.apache.commons.lang3.StringUtils;
import jp.co.jsbank.mobile.bff.common.ErrorCodeConstant;
import jp.co.jsbank.mobile.bff.common.JsonResult;
import jp.co.jsbank.mobile.bff.common.exception.BadRequestException;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.EkycFinishVerificationRequestDto;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.GetTokenRequestDTO;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.GetTokenResponseDTO;
import jp.co.jsbank.mobile.bff.service.LiquidEkycService;

/**
 * LIQUID EKYCコントロール
 */
@RestController
public class LiquidEkycController {

	@Resource
    private LiquidEkycService liquidEkycService;

	/**
	 * トークン取得
	 * @param requestDto
	 * @return JsonResult
	 * @throws BadRequestException
	 * @throws Exception
	 */
	@PostMapping("/ekyc/AB1201")
	public String getEkycToken(@RequestBody GetTokenRequestDTO requestDto) {

		GetTokenResponseDTO result = liquidEkycService.getToken(requestDto);
		// TODO 削除予定
		if (StringUtils.isNotBlank(requestDto.getErrorRedirectUrl())) {
			throw new BadRequestException(requestDto.getErrorRedirectUrl(), "errorRedirectUrl");
		}
		return JsonResult.success(result);
	}

	/**
	 * 完了処理 jpki容貌方式
	 * @param requestDto
	 * @return
	 */
	@PostMapping("ekyc/AB1202")
	public String setFinishVerificationWithJpki(@RequestBody EkycFinishVerificationRequestDto requestDto) {

		if (StringUtils.isAnyBlank(requestDto.getApplicantId(),
				requestDto.getFirstName(),
				requestDto.getLastName(),
				requestDto.getBirthday(),
				requestDto.getSex(),
				requestDto.getZipCode(),
				requestDto.getAddress1())) {
			throw new BadRequestException(ErrorCodeConstant.EKYC_CODE_MBEK0001, "必須パラメータが不足しています。");
		}

		String result = liquidEkycService.setFinishVerificationWithJpki(requestDto);
		return JsonResult.success(result);
	}

	/**
	 * 完了処理 へ方式
	 * @param requestDto
	 * @return
	 */
	@PostMapping("ekyc/AB1203")
	public String setFinishVerificationWithHe(@RequestBody EkycFinishVerificationRequestDto requestDto) {

		if (StringUtils.isAnyBlank(requestDto.getApplicantId(),
				requestDto.getFirstName(),
				requestDto.getLastName(),
				requestDto.getBirthday(),
				requestDto.getSex(),
				requestDto.getZipCode(),
				requestDto.getAddress1())) {
			throw new BadRequestException(ErrorCodeConstant.EKYC_CODE_MBEK0001, "必須パラメータが不足しています。");
		}

		String result = liquidEkycService.setFinishVerificationWithHe(requestDto);
		return JsonResult.success(result);
	}

}
