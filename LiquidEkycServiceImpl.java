package jp.co.jsbank.mobile.bff.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import javax.annotation.Resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson.JSONObject;

import jp.co.jsbank.mobile.bff.common.ErrorCodeConstant;
import jp.co.jsbank.mobile.bff.common.RequestHeaderContext;
import jp.co.jsbank.mobile.bff.common.builder.ParameterBuilder;
import jp.co.jsbank.mobile.bff.common.code.ApplyStatusCode;
import jp.co.jsbank.mobile.bff.common.code.PersonalityFlagCode;
import jp.co.jsbank.mobile.bff.common.exception.InternalServerErrorException;
import jp.co.jsbank.mobile.bff.common.icos.IcosHandler;
import jp.co.jsbank.mobile.bff.common.util.NameParser.ParsedName;
import jp.co.jsbank.mobile.bff.config.EkycSdkClient;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.EkycGetPhotosResponseDto;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.EkycIdDocumentInformationResponseDto;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.EkycKycResultRequestDto;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.EkycPhotosInformationDto;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.EkycRequestInformationRequestDto;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.EkycRequestInformationResponseDto;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.EkycVerificationJoinedData;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.EkycVerificationResultResponseDto;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.GetTokenRequestDTO;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.GetTokenResponseDTO;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.OptionalVerificationResultDto;
import jp.co.jsbank.mobile.bff.logic.AccountOpeningLogic;
import jp.co.jsbank.mobile.bff.logic.CashCardIssueLogic;
import jp.co.jsbank.mobile.bff.service.LiquidEkycService;
import jp.co.jsbank.mobile.bff.common.Constant;
import jp.co.jsbank.mobile.bff.common.EkycIdDocumentTypeCode;
import jp.co.jsbank.mobile.bff.common.code.DocumentTypeCode;
import jp.co.jsbank.mobile.bff.common.util.GenerateVerificationResultUtils;

import org.springframework.beans.factory.annotation.Value;

import jp.co.jsbank.mobile.bff.dto.FileStreamDto;
import jp.co.jsbank.mobile.bff.dto.IdentityVerificationDocumentsDto;
import jp.co.jsbank.mobile.bff.dto.aba.AccountOpenAppliContentRegistrationIndividualAbaRequestDto;
import jp.co.jsbank.mobile.bff.dto.aba.AccountOpenAppliContentUpdateCorporationAbaRequestDto;
import jp.co.jsbank.mobile.bff.dto.aba.AccountOpenAppliContentUpdateSelfempAbaRequestDto;
import jp.co.jsbank.mobile.bff.dto.aba.CcIssuingApplicationContentRegistrationAbaRequestDto;
import jp.co.jsbank.mobile.bff.dto.aba.CcIssuingApplicationContentRegistrationAbaResponseDto;
import jp.co.jsbank.mobile.bff.dto.aba.IndividualAbaResponseDto;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.EkycApiEndpoints;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.EkycCommonPhotoDto;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.EkycFinishVerificationRequestDto;

import jp.co.jsbank.mobile.bff.common.util.NameParser;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * トークンサービス
 */
@Service
public class LiquidEkycServiceImpl implements LiquidEkycService {

    final String HEADER_API_KEY = "X-Ekyc-Api-Key";
    final String APPLICANT_ID = "applicant_id";

    @Autowired
    @Qualifier("ekycSdkClient")
    private EkycSdkClient ekycClient;

    /**
     * APIKEY.JAG-POC-BUCKET用のものを設定.
     */
    @Value("${liquid_ekyc_connector_url}")
    private String liquidConnectorUrl;
    /**
     * アクセスキー
     */
    @Value("${liquid_ekyc_connector_api_key}")
    private String liquidApiKey;

    // トークン取得
    private final String GET_TOKEN = "/v1/sdk/applications";
    // 申請情報登録
    private final String REQUEST_INFORMATIONS = "/v1/kyc_request_informations";

    private final String EXPIRED = "/v1/applicant_expired_requests?applicant_expired_date=20250101";
    private Logger logger = LoggerFactory.getLogger(LiquidEkycServiceImpl.class);

    @Autowired
    private IcosHandler icosHandler;

    @Resource
    private AccountOpeningLogic accountOpeningLogic;

    @Resource
    private CashCardIssueLogic cashcardIssueLogic;

    private String FACE_PHOTO_TYPE = "04";
    // eKYC判定結果画像
    private String VERFICATION_RESULT_TYPE = DocumentTypeCode.DOCUMENTTYPE_05;

    // 代表者本人
    private String UPDATE_TYPE_PERSONAL_013 = "013";
    // 事業先
    private String UPDATE_TYPE_BUSINESS_017 = "017";

    // ekyc result OK
    private String EKYC_RESULT_OK = "0";
    // ekyc result NG
    private String EKYC_RESULT_NG = "1";

    // japan
    private String NATIONALITY_JP = "jp";

    // 0：同一人物（他人受入率1/100,000以下、判定スコア値388以上） など
    private String FACE_VERIFICATION_RESULT_OK = "0";

    // 本人確認書類の顔写真と顔「正面」画像の同一致判定処理の結果
    // 0：高
    private String FACE_PHOTO_VERIFICATION_RESULT_HIGH = "0";
    // 1：中
    private String FACE_PHOTO_VERIFICATION_RESULT_MIDEUM = "1";
    // 2：低
    private String FACE_PHOTO_VERIFICATION_RESULT_LOW = "2";
    // 本物の本人確認書類
    private String ID_DOCUMENT_VERIFICATION_RESULT_REAL = "0";
    // 固定値 false
    // 本人確認（へ方式）の場合、falseを指定する
    // 公的個人認証を利用する場合、falseを指定する.
    private Boolean HAS_NO_SENSITIVE_INFO_FLAG = false;

    // App EKYC_CHECKED
    private String APP_EKYC_CHECKED = "0";

    private String APP_EKYC_UNCHECKED = "1";

    /**
     * 口座開設・CC発行向けのICOSバケット
     */
    @Value("${icos_bucket_name}")
    private String icosBucketName;
    /**
     * エンドポイント
     */
    @Value("${icos_public_endpoint}")
    private String icosPublicEndpoint;

    /**
     * トークンIDを追加する(IDが既に存在したら、既存のトークンを返す)
     *
     * @param tokenIdEntity トークンIDエンティティ
     */
    @Override
    public GetTokenResponseDTO getToken(GetTokenRequestDTO requestDTO) {
        logger.info("ekyc getToken");

        try {
            logger.info("ekyc before");
            String url = liquidConnectorUrl + GET_TOKEN;
            if (requestDTO.getApplicantId() == null || requestDTO.getApplicantId().isEmpty()) {
                requestDTO.setApplicantId(createApplicantId());
            }
            logger.info(url + "::REQ-> " + JSONObject.toJSONString(requestDTO));

            // SDK本人確認申請APIを呼び出す（ApiKey・Content-TypeはEkycSdkClientが付与）
            GetTokenResponseDTO body = ekycClient.execute(url,
                    HttpMethod.POST, requestDTO, GetTokenResponseDTO.class);

            body.setApplicantId(requestDTO.getApplicantId());
            return body;
        } catch (InternalServerErrorException e) {
            // MBAP採番済のeKYC業務エラーはそのまま送出（GlobalExceptionHandlerで処理）
            throw e;
        } catch (RestClientResponseException e) {
            logger.info("ekyc RestClientResponseException，statusCode={}, responseBody={}",
                    e.getRawStatusCode(),
                    e.getResponseBodyAsString(),
                    e);
            throw e;

        } catch (RestClientException e) {
            logger.info("ekyc RestClientException:", e);
            throw e;
        } catch (Exception e) {
            logger.info("ekyc exception:", e.getMessage());
            logger.info("ekyc exception", e);
            throw new InternalServerErrorException(ErrorCodeConstant.ERROR_CODE_MBAP0010, "");
        }
    }

    private String requestInformation(EkycRequestInformationRequestDto requestDto) {
        String applicantId = requestDto.getApplicantId();
        logger.info(applicantId + " " + "申請情報登録API start");
        try {
            // ParameterBuilder parameterDto = ParameterBuilder.buildLiquidEkyc(requestDto,
            // null);

            // String fullUrlPath =
            // EkycApiEndpoints.buildFullUrl(EkycApiEndpoints.KYC_REQUEST_INFORMATION);
            // String fullUrlPath = liquidConnectorUrl + REQUEST_INFORMATIONS;
            // logger.info("ekyc requestInformation url={}", fullUrlPath);

            // EkycRequestInformationResponseDto response = ekycClient.execute(fullUrlPath,
            // HttpMethod.POST,
            // parameterDto,EkycRequestInformationResponseDto.class);

            String fullUrlPath = liquidConnectorUrl + REQUEST_INFORMATIONS;
            logger.info("ekyc requestInformation url={}", fullUrlPath);

            // 申請情報登録APIを呼び出す（ApiKey・Content-TypeはEkycSdkClientが付与）
            EkycRequestInformationResponseDto response = ekycClient.execute(
                    fullUrlPath,
                    HttpMethod.POST, requestDto,
                    EkycRequestInformationResponseDto.class);

            logger.info(applicantId + " " + "申請情報登録API end");
            logger.info("ekyc requestInformation after, response={}",
                    response != null ? JSONObject.toJSONString(response) : "null");

        } catch (InternalServerErrorException e) {
            logger.error("ekyc requestInformation InternalServerErrorException mbapId={} message={}", e.getCode(),
                    e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("ekyc requestInformation Exception message={}", e.getMessage(), e);
            throw e;
        }
        return Constant.OK;
    }

    /**
     * 画像データ取得 API GET
     * 
     * @param applicantId 連携ID
     * @return 画像データリスポンス
     */
    private EkycGetPhotosResponseDto getPhotos(String applicantId) {

        EkycGetPhotosResponseDto response = null;
        logger.info(applicantId + " " + "画像データ取得API start");
        try {
            /* TODO applicant id null check */

            String fullUrlPath = EkycApiEndpoints.buildFullUrl(liquidConnectorUrl, EkycApiEndpoints.GET_PHOTOS,
                    applicantId);

            response = ekycClient.execute(
                    fullUrlPath,
                    HttpMethod.GET,
                    null,
                    EkycGetPhotosResponseDto.class);

            logger.info(applicantId + " " + "画像データ取得API end");
            if (response.getIdDocumentPhotos() == null) {
                logger.info(applicantId + " " + " IdDocumentPhotos is null");
            } else {
                for (EkycPhotosInformationDto documentPhoto : response.getIdDocumentPhotos()) {
                    logger.info(applicantId + " " + "FileName = " + documentPhoto.getFileName());
                }
            }

        } catch (InternalServerErrorException e) {
            throw e;
        }
        return response;
    }

    /**
     * 自動判定結果取得 API
     * 
     * @param applicantId 連携ID
     * @return 判定結果リスポンス
     */
    private EkycVerificationResultResponseDto getVerificationResult(String applicantId) {

        EkycVerificationResultResponseDto response = null;
        logger.info(applicantId + " " + "自動判定結果取得API start");
        try {
            /* TODO applicant id null check */

            String fullUrlPath = EkycApiEndpoints.buildFullUrl(liquidConnectorUrl,
                    EkycApiEndpoints.VERIFICATION_RESULTS, applicantId);

            response = ekycClient.execute(
                    fullUrlPath,
                    HttpMethod.GET,
                    null,
                    EkycVerificationResultResponseDto.class);

            logger.info(applicantId + " " + "自動判定結果取得API end");
            logger.info("ekyc verification result={}", JSONObject.toJSONString(response));

        } catch (InternalServerErrorException e) {
            throw e;
        }
        return response;
    }

    /**
     * ICカード読取取得 API
     * 
     * @param applicantId 連携ID
     * @return 結果リスポンス
     * 
     */
    private EkycIdDocumentInformationResponseDto getIdDocumentInformation(String applicantId) {
        logger.info(applicantId + " " + "ICカード読取取得API start");
        EkycIdDocumentInformationResponseDto response = null;

        try {
            /* TODO applicant id null check */

            String fullUrlPath = EkycApiEndpoints.buildFullUrl(liquidConnectorUrl,
                    EkycApiEndpoints.GET_ID_DOCUMENT_INFORMATION, applicantId);

            response = ekycClient.execute(
                    fullUrlPath,
                    HttpMethod.GET,
                    null,
                    EkycIdDocumentInformationResponseDto.class);

            logger.info(applicantId + " " + "ICカード読取取得API end");

        } catch (InternalServerErrorException e) {
            logger.info("ekyc getIdDocumentInformation InternalServerErrorException:" + e.getMessage());
            throw e;
        }
        return response;
    }

    /**
     * 本人確認結果登録 API
     * 
     * @param requestDto
     * @return 結果リスポンス ステータス
     * 
     */
    private String getKycResult(EkycKycResultRequestDto requestDto) {
        String applicantId = requestDto.getApplicantId();
        logger.info(applicantId + " " + "本人確認結果登録API start");

        try {
            /* TODO Check parameter */
            // HttpHeaders requestHeaders = new HttpHeaders();
            // requestHeaders.add(HEADER_API_KEY, liquidApiKey);
            // requestHeaders.add("Content-Type", "application/json");

            // ParameterBuilder parameterDto = ParameterBuilder.buildLiquidEkyc(requestDto,
            // requestHeaders);
            // String fullUrlPath = EkycApiEndpoints.buildFullUrl(liquidConnectorUrl,
            // EkycApiEndpoints.KYC_RESULT);
            // EkycKycResultResponseDto response =
            // ekycClient.execute(fullUrlPath,HttpMethod.POST,parameterDto,EkycKycResultResponseDto.class);

            String url = liquidConnectorUrl + "/v1/kyc_results";

            // 本人確認結果登録APIを呼び出す（ApiKey・Content-TypeはEkycSdkClientが付与）
            ekycClient.execute(url, HttpMethod.POST, requestDto, Void.class);
            logger.info(applicantId + " " + "本人確認結果登録API end");

        } catch (InternalServerErrorException e) {
            logger.info("ekyc kyc result InternalServerErrorException:" + e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.info("ekyc kyc result Exception:" + e.getMessage());
        }
        return Constant.OK;
    }

    private String createApplicantId() {
        int randomNumber = ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000);
        return String.valueOf(System.currentTimeMillis()) + randomNumber;
    }

    public String setFinishVerificationWithJpki(EkycFinishVerificationRequestDto finishVerification) {
        String ekycApiInterface = "JPKI+容貌方式";
        String applicantId = finishVerification.getApplicantId();
        logger.info(applicantId + " " + "完了処理 start");
        logger.info(applicantId + " request={}", JSONObject.toJSONString(finishVerification));
        // 申請情報登録API

        /* 申請情報登録 処理結果 */
        String requestResult = Constant.FAIL;

        EkycRequestInformationRequestDto myRequest = createRequestInformationDto(finishVerification);
        logger.info("ekyc setFinishVerificationWithJpki createRequestInformationDto done");

        try {
            requestResult = requestInformation(myRequest);
            logger.info("setFinishVerificationWithJpki requestInformation result={}", requestResult);
        } catch (InternalServerErrorException e) {
            logger.error(
                    "setFinishVerificationWithJpki requestInformation InternalServerErrorException mbapId={} message={}",
                    e.getCode(), e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("setFinishVerificationWithJpki requestInformation Exception message={}", e.getMessage(), e);
            throw e;
        }

        if (StringUtils.equals(Constant.FAIL, requestResult)) {
            logger.error("setFinishVerificationWithJpki requestResult=FAIL, returning null");
            return null;
        }

        /* 情報取得 */
        EkycVerificationJoinedData verificationJoinedData = null;
        try {
            verificationJoinedData = requestAllData(myRequest);
            logger.info("setFinishVerificationWithJpki requestAllData done, verificationResult={}",
                    verificationJoinedData != null && verificationJoinedData.getVerificationResult() != null ? "exists"
                            : "null");
        } catch (InternalServerErrorException e) {
            logger.error("setFinishVerificationWithJpki requestAllData InternalServerErrorException mbapId={} message={}",
                    e.getCode(), e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("setFinishVerificationWithJpki requestAllData Exception message={}", e.getMessage(), e);
            throw e;
        }

        // 自動判定結果利用
        EkycVerificationResultResponseDto verificationResult = verificationJoinedData.getVerificationResult();
        if (Objects.isNull(verificationResult)) {
            logger.error("setFinishVerificationWithJpki verificationResult is null");
            return null;
        }

        String checkResult = checkTheVerificationResults(verificationResult);
        logger.info("setFinishVerificationWithJpki checkTheVerificationResults={}", checkResult);

        if (StringUtils.equals(Constant.FAIL, checkResult)) {
            logger.error("setFinishVerificationWithJpki checkTheVerificationResults=FAIL");
            return null;
        }

        // 本人容貌画像と書類画像保存
        if (Objects.isNull(verificationJoinedData)) {
            logger.error("setFinishVerificationWithJpki verificationJoinedData is null (unexpected)");
            return null;
        }

        if (Objects.isNull(verificationJoinedData.getPhotos())) {
            logger.error("setFinishVerificationWithJpki verificationJoinedData photo is null");
            return null;
        }

        List<IdentityVerificationDocumentsDto> list = storeAllImagesToICOS(verificationJoinedData, finishVerification,
                ekycApiInterface);
        logger.info("setFinishVerificationWithJpki storeAllImagesToICOS done, list size={}",
                list != null ? list.size() : 0);

        if (Objects.isNull(verificationJoinedData.getIdDocumentInformation())) {
            logger.error("setFinishVerificationWithJpki getIdDocumentInformation is null");
            return null;
        }

        if (Objects.isNull(list) || list.isEmpty()) {
            logger.error("setFinishVerificationWithJpki storeAllImagesToICOS list is null or empty");
            return null;
        }

        String receptionNumber = finishVerification.getReceptionNumber();

        receptionNumber = storeRequestInformationToABA(finishVerification, verificationJoinedData, list);
        logger.info("setFinishVerificationWithJpki storeRequestInformationToABA done, receptionNumber={}",
                receptionNumber);

        EkycKycResultRequestDto kycRequest = new EkycKycResultRequestDto();

        try {
            String ekycResult = getTheKycResult(myRequest, finishVerification, true);

            kycRequest.setApplicantId(applicantId);
            kycRequest.setKycResult(ekycResult);
            kycRequest.setHasSensitiveInfo(HAS_NO_SENSITIVE_INFO_FLAG);

            String kycRequestResult = getKycResult(kycRequest);

            if (!StringUtils.equals(Constant.OK, kycRequestResult)) {
                logger.info("kycRequestResult = " + kycRequestResult);
            }
        } catch (Exception e) {
            logger.info("結果登録api error:", e);
            throw new InternalServerErrorException(ErrorCodeConstant.EKYC_ERROR_CODE_0001, "本人確認結果登録API error");
        }

        return receptionNumber;

    }

    public String setFinishVerificationWithHe(EkycFinishVerificationRequestDto finishVerification) {
        String ekycApiInterface = "へ方式";
        String applicantId = finishVerification.getApplicantId();
        logger.info(applicantId + " " + "完了処理 start");
        logger.info(applicantId + " request={}", JSONObject.toJSONString(finishVerification));
        // 申請情報登録API

        /* 申請情報登録 処理結果 */
        String requestResult = Constant.FAIL;

        EkycRequestInformationRequestDto myRequest = createRequestInformationDto(finishVerification);
        logger.info("ekyc setFinishVerificationWithJpki createRequestInformationDto done");

        try {
            requestResult = requestInformation(myRequest);
            logger.info("setFinishVerificationWithJpki requestInformation result={}", requestResult);
        } catch (InternalServerErrorException e) {
            logger.error(
                    "setFinishVerificationWithJpki requestInformation InternalServerErrorException mbapId={} message={}",
                    e.getCode(), e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("setFinishVerificationWithJpki requestInformation Exception message={}", e.getMessage(), e);
            throw e;
        }

        if (StringUtils.equals(Constant.FAIL, requestResult)) {
            logger.error("setFinishVerificationWithJpki requestResult=FAIL, returning null");
            return null;
        }

        /* 情報取得 */
        EkycVerificationJoinedData verificationJoinedData = null;
        try {
            verificationJoinedData = requestAllData(myRequest);
            logger.info("setFinishVerificationWithJpki requestAllData done, verificationResult={}",
                    verificationJoinedData != null && verificationJoinedData.getVerificationResult() != null ? "exists"
                            : "null");
        } catch (InternalServerErrorException e) {
            logger.error("setFinishVerificationWithJpki requestAllData InternalServerErrorException mbapId={} message={}",
                    e.getCode(), e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("setFinishVerificationWithJpki requestAllData Exception message={}", e.getMessage(), e);
            throw e;
        }

        // 自動判定結果利用
        EkycVerificationResultResponseDto verificationResult = verificationJoinedData.getVerificationResult();
        if (Objects.isNull(verificationResult)) {
            logger.error("setFinishVerificationWithJpki verificationResult is null");
            return null;
        }

        String checkResult = checkTheVerificationResults(verificationResult);
        logger.info("setFinishVerificationWithJpki checkTheVerificationResults={}", checkResult);

        if (StringUtils.equals(Constant.FAIL, checkResult)) {
            logger.error("setFinishVerificationWithJpki checkTheVerificationResults=FAIL");
            return null;
        }

        // 本人容貌画像と書類画像保存
        if (Objects.isNull(verificationJoinedData)) {
            logger.error("setFinishVerificationWithJpki verificationJoinedData is null (unexpected)");
            return null;
        }

        if (Objects.isNull(verificationJoinedData.getPhotos())) {
            logger.error("setFinishVerificationWithJpki verificationJoinedData photo is null");
            return null;
        }

        List<IdentityVerificationDocumentsDto> list = storeAllImagesToICOS(verificationJoinedData, finishVerification,
                ekycApiInterface);
        logger.info("setFinishVerificationWithHe storeAllImagesToICOS done, list size={}",
                list != null ? list.size() : 0);

        if (Objects.isNull(verificationJoinedData.getIdDocumentInformation())) {
            logger.error("setFinishVerificationWithJpki getIdDocumentInformation is null");
            return null;
        }

        if (Objects.isNull(list) || list.isEmpty()) {
            logger.error("setFinishVerificationWithHe storeAllImagesToICOS list is null or empty");
            return null;
        }

        String receptionNumber = finishVerification.getReceptionNumber();

        receptionNumber = storeRequestInformationToABA(finishVerification, verificationJoinedData, list);
        logger.info("setFinishVerificationWithJpki storeRequestInformationToABA done, receptionNumber={}",
                receptionNumber);

        EkycKycResultRequestDto kycRequest = new EkycKycResultRequestDto();

        try {
            String ekycResult = getTheKycResult(myRequest, finishVerification, true);

            kycRequest.setApplicantId(applicantId);
            kycRequest.setKycResult(ekycResult);
            kycRequest.setHasSensitiveInfo(HAS_NO_SENSITIVE_INFO_FLAG);

            String kycRequestResult = getKycResult(kycRequest);

            if (!StringUtils.equals(Constant.OK, kycRequestResult)) {
                logger.info("kycRequestResult = " + kycRequestResult);
            }
        } catch (Exception e) {
            logger.info("結果登録api error:", e);
            throw new InternalServerErrorException(ErrorCodeConstant.EKYC_ERROR_CODE_0001, "本人確認結果登録API error");
        }

        return receptionNumber;
    }

    private String checkTheVerificationResults(EkycVerificationResultResponseDto verificationResult) {

        OptionalVerificationResultDto result = verificationResult.getOptionalVerificationResult();

        if (Objects.isNull(result)) {
            logger.info("ekyc result data is null");
            throw new InternalServerErrorException(ErrorCodeConstant.EKYC_ERROR_CODE_0001, "自動判定結果取得失敗しました");
        }

        return Constant.OK;
    }

    private EkycRequestInformationRequestDto createRequestInformationDto(
            EkycFinishVerificationRequestDto finishVerification) {

        EkycRequestInformationRequestDto requestDto = new EkycRequestInformationRequestDto();

        String applicantId = finishVerification.getApplicantId();
        String lastName = finishVerification.getLastName();
        String firstName = finishVerification.getFirstName();
        String middleName = finishVerification.getMiddleName();
        String lastNameKana = finishVerification.getLastNameKana();
        String firstNameKana = finishVerification.getFirstNameKana();
        String middleNameKana = finishVerification.getMiddleNameKana();
        String birthday = finishVerification.getBirthday();

        String sex = finishVerification.getSex();
        String zipCode = finishVerification.getZipCode();
        String phoneNumber = finishVerification.getPhoneNumber();
        String address1 = finishVerification.getAddress1();
        String address2 = finishVerification.getAddress2();
        String address3 = finishVerification.getAddress3();
        String address4 = finishVerification.getAddress4();
        String idNumber = finishVerification.getIdNumber();
        // TODO ekyc add some check logic
        if (StringUtils.isBlank(applicantId)) {
            logger.info("ekyc applicantId is null");
        }
        requestDto.setApplicantId(applicantId);

        // 姓・名の必須チェックは Controller の入口で実施済み（MBAP1301）。ここではログのみ。
        if (StringUtils.isBlank(lastName)) {
            logger.info("ekyc lastName is null");
        }
        requestDto.setLastName(lastName);

        if (StringUtils.isBlank(firstName)) {
            logger.info("ekyc firstName is null");
        }
        requestDto.setFirstName(firstName);

        requestDto.setMiddleName(middleName);
        requestDto.setLastNameKana(lastNameKana);
        requestDto.setFirstNameKana(firstNameKana);
        requestDto.setMiddleNameKana(middleNameKana);

        if (StringUtils.isBlank(birthday)) {
            logger.info("ekyc birthday is null");
        }
        requestDto.setBirthday(birthday.replace("-", ""));

        if (StringUtils.isBlank(zipCode)) {
            logger.info("ekyc zipCode is null");
        }
        requestDto.setZipCode(zipCode);

        if (StringUtils.isBlank(sex)) {
            logger.info("ekyc sex is null");
        }
        requestDto.setSex(sex);
        // "jp" setting
        requestDto.setNationality(NATIONALITY_JP);
        requestDto.setPhoneNumber(phoneNumber);

        requestDto.setAddress1(address1);
        requestDto.setAddress2(address2);
        requestDto.setAddress3(address3);
        requestDto.setAddress4(address4);
        requestDto.setIdNumber(idNumber);

        return requestDto;

    }

    /**
     * ekyc result 判定結果
     */
    private String getTheKycResult(EkycRequestInformationRequestDto requestDto,
            EkycFinishVerificationRequestDto finishVerification, Boolean isJpki) {
        // TODO eKYC need to add some logic ?
        // JPKI
        if (isJpki) {
            return EKYC_RESULT_OK;
        }
        // He
        return EKYC_RESULT_OK;
    }

    private EkycVerificationJoinedData requestAllData(EkycRequestInformationRequestDto requestDto) {

        // 画像データ get photo
        CompletableFuture<EkycGetPhotosResponseDto> photosFuture = CompletableFuture.supplyAsync(() ->

        getPhotos(requestDto.getApplicantId())

        );

        // get check
        // 自動判定結果
        CompletableFuture<EkycVerificationResultResponseDto> verificationResultFuture = CompletableFuture
                .supplyAsync(() -> {
                    EkycVerificationResultResponseDto result = getVerificationResult(requestDto.getApplicantId());

                    final int MAX_RETRY = 12;

                    for (int retryCount = 0; retryCount < MAX_RETRY && !isFacePhotoReady(result); retryCount++) {
                        try {
                            TimeUnit.SECONDS.sleep(5);
                            logger.info(requestDto.getApplicantId() + " " + "自動判定結果取得API リトライ (" + (retryCount + 1)
                                    + "/" + MAX_RETRY + ")");
                        } catch (Exception e) {
                            logger.info("ekyc sleep error:" + e.getMessage());
                            throw new InternalServerErrorException(ErrorCodeConstant.EKYC_ERROR_CODE_0001,
                                    e.getMessage());
                        }
                        result = getVerificationResult(requestDto.getApplicantId());
                    }

                    if (result != null && result.getOptionalVerificationResult() == null) {
                        logger.info("ekyc retry result is still null");
                        // 自動判定結果取得API：リトライ回数Maxでもnull → 一律MBAP1300
                        throw new InternalServerErrorException(ErrorCodeConstant.ERROR_CODE_MBAP1300,
                                "自動判定結果取得に失敗しました（リトライ上限）");
                    }
                    return result;
                });

        // *ICカード読取情報取得
        CompletableFuture<EkycIdDocumentInformationResponseDto> idDocumentInformationFuture = CompletableFuture
                .supplyAsync(() -> getIdDocumentInformation(requestDto.getApplicantId()));

        try {
            // すべての非同期処理の完了を待つ（いずれか失敗時はここで例外送出）
            CompletableFuture.allOf(photosFuture, verificationResultFuture, idDocumentInformationFuture).join();

            EkycGetPhotosResponseDto photos = photosFuture.get();
            EkycVerificationResultResponseDto verificationResult = verificationResultFuture.get();
            EkycIdDocumentInformationResponseDto idDocumentsInformation = idDocumentInformationFuture.get();
            return new EkycVerificationJoinedData(requestDto, photos, verificationResult, idDocumentsInformation);

        } catch (Exception e) {

            // 各非同期処理の元例外がeKYC業務エラー（MBAP採番済）なら、そのまま送出してMBAPを維持する
            if (photosFuture.isCompletedExceptionally()) {
                Throwable ex = getOriginalException(photosFuture);
                if (ex instanceof InternalServerErrorException) {
                    throw (InternalServerErrorException) ex;
                }
            }
            if (verificationResultFuture.isCompletedExceptionally()) {
                Throwable ex = getOriginalException(verificationResultFuture);
                if (ex instanceof InternalServerErrorException) {
                    throw (InternalServerErrorException) ex;
                }
            }
            if (idDocumentInformationFuture.isCompletedExceptionally()) {
                Throwable ex = getOriginalException(idDocumentInformationFuture);
                if (ex instanceof InternalServerErrorException) {
                    throw (InternalServerErrorException) ex;
                }
            }

            // 上記以外は予期せぬエラー（共通：MBAP1300）
            logger.error("ekyc requestAllData 予期せぬエラー", e);
            throw new InternalServerErrorException(ErrorCodeConstant.ERROR_CODE_MBAP1300,
                    "eKYC情報取得で予期せぬエラーが発生しました");
        }

    }

    private boolean isFacePhotoReady(EkycVerificationResultResponseDto result) {
        return result != null
                && result.getOptionalVerificationResult() != null;
    }

    private Throwable getOriginalException(CompletableFuture<?> future) {
        try {
            future.join();
        } catch (Exception ex) {

            Throwable cause = ex;
            while (cause != null) {

                if (cause instanceof InternalServerErrorException) {
                    return (InternalServerErrorException) cause;
                }
                cause = cause.getCause();
            }
            return ex;
        }

        return null;

    }

    /**
     * ①ekyc取得の本人容貌画像、②前端連携のカード内保存人物画像（空可）、③生成した結果画像の
     * 3種類の画像アップロードを1つにまとめる。ファイル名生成は既存ロジックに統一。
     * 失敗時の挙動は従来通り：①③は中断、②は握りつぶして継続。
     */
    private List<IdentityVerificationDocumentsDto> storeAllImagesToICOS(EkycVerificationJoinedData data,
            EkycFinishVerificationRequestDto requestDto, String apiInterface) {

        List<IdentityVerificationDocumentsDto> list = new ArrayList<>();
        String applicantId = data.getRequestInformation().getApplicantId();
        int index = 1;

        // ① ekyc取得の本人容貌画像（正面）：失敗時は中断
        if (Objects.nonNull(data) && data.photoIsExist()) {
            EkycCommonPhotoDto facePhoto = data.getPhotos().getFaceFrontPhoto();
            if (Objects.nonNull(facePhoto) && !StringUtils.isBlank(facePhoto.getImage())
                    && !StringUtils.isBlank(facePhoto.getFileName())) {
                logger.info(applicantId + " " + "本人容貌画像アップロード start");
                try {
                    String imagePayload = facePhoto.getImage();
                    int commaIndex = imagePayload.indexOf(',');
                    if (imagePayload.startsWith("data:") && commaIndex != -1) {
                        imagePayload = imagePayload.substring(commaIndex + 1);
                    }
                    IdentityVerificationDocumentsDto faceDoc = uploadImageToICOS(applicantId,
                            facePhoto.getFileName(), imagePayload, FACE_PHOTO_TYPE, index);
                    list.add(faceDoc);
                    index++;
                } catch (Exception e) {
                    // 画像ファイルのアップロード失敗 → 一律MBAP1300
                    logger.error(applicantId + " 本人容貌画像アップロード failed", e);
                    throw new InternalServerErrorException(ErrorCodeConstant.ERROR_CODE_MBAP1300,
                            "Failed to store iCOS");
                }
                logger.info(applicantId + " " + "本人容貌画像アップロード end");
            }
        }

        // ② 前端から連携されたカード内保存の人物画像（空の可能性あり）：失敗時は握りつぶして継続
        List<FileStreamDto> fileStreams = requestDto.getFileStreams();
        if (Objects.nonNull(fileStreams) && !fileStreams.isEmpty()) {
            for (FileStreamDto fileStreamDto : fileStreams) {
                if (!StringUtils.isBlank(fileStreamDto.getFileStream())) {
                    logger.info(applicantId + " " + "ファイルストリーム アップロード start");
                    try {
                        IdentityVerificationDocumentsDto cardDoc = uploadImageToICOS(applicantId,
                                fileStreamDto.getFileName(), fileStreamDto.getFileStream(),
                                VERFICATION_RESULT_TYPE, index);
                        list.add(cardDoc);
                        index++;
                    } catch (Exception e) {
                        logger.info(applicantId + " ファイルストリーム アップロード failed", e);
                    }
                    logger.info(applicantId + " " + "ファイルストリーム アップロード end");
                }
            }
        }

        // ③-1 結果画像作成：データ不備等で失敗した場合はリトライ不可として MBAP1300 を返す
        String base64;
        try {
            logger.info(applicantId + " " + "結果画像作成 start");
            base64 = GenerateVerificationResultUtils.createBase64WithVerification(data, apiInterface);
            logger.info(applicantId + " " + "結果画像作成 end");
        } catch (Exception e) {
            logger.error(applicantId + " 結果画像作成 failed:" + e.getMessage(), e);
            throw new InternalServerErrorException(ErrorCodeConstant.ERROR_CODE_MBAP1300, "結果画像作成失敗");
        }

        // ③-2 結果画像アップロード：既存の処理を踏襲し、失敗時は中断
        try {
            if (!StringUtils.isBlank(base64)) {
                IdentityVerificationDocumentsDto resultDoc = uploadImageToICOS(applicantId, null, base64,
                        VERFICATION_RESULT_TYPE, index);
                list.add(resultDoc);
                index++;
            } else {
                logger.info("result image is blank");
            }
        } catch (Exception e) {
            logger.info(applicantId + " 結果画像アップロード failed:" + e.getMessage());
            logger.info(applicantId + " 結果画像アップロード failed:", e);
            throw new InternalServerErrorException(ErrorCodeConstant.EKYC_ERROR_CODE_0001, "ICOSへの書き込み失敗");
        }

        return list;
    }

    /**
     * 画像を1件 ICOS へアップロードし、本人確認書類DTOを組み立てて返す。
     * ファイル名は buildIcosFileName に統一。アップロード失敗時は例外を上位へ伝播する。
     */
    private IdentityVerificationDocumentsDto uploadImageToICOS(String applicantId, String originalFileName,
            String payload, String documentType, int index) throws Exception {

        String fileName = buildIcosFileName(originalFileName, index);

        logger.info(applicantId + " ファイルストリーム:" + fileName + " start");
        icosHandler.addFile(fileName, payload);
        logger.info(applicantId + " ファイルストリーム:" + fileName + " end");

        IdentityVerificationDocumentsDto identityDocument = new IdentityVerificationDocumentsDto();
        identityDocument.setDocumentsType(documentType);
        identityDocument.setEndPoint(icosPublicEndpoint);
        identityDocument.setBucket(icosBucketName);
        identityDocument.setObjectPath(fileName);
        return identityDocument;
    }

    /**
     * アップロードファイル名を生成する。
     * 元ファイル名がある場合は既存の replaceFileName ロジックを流用し、
     * 無い場合（結果画像など）は「タイムスタンプ-index-端末ID.jpeg」で組み立てる。
     */
    private String buildIcosFileName(String originalFileName, int index) {
        if (StringUtils.isBlank(originalFileName)) {
            String deviceId = "sm"
                    + RequestHeaderContext.getInstance().getDeviceId().toLowerCase().replaceAll("-", "");
            String timeStamp = String.valueOf(System.currentTimeMillis());
            return timeStamp + "-" + index + "-" + deviceId + ".jpeg";
        }
        return replaceFileName(originalFileName, index);
    }

    private String storeRequestInformationToABA(EkycFinishVerificationRequestDto finishVerification,
            EkycVerificationJoinedData data, List<IdentityVerificationDocumentsDto> list) {

        if (Objects.isNull(data) || data.idDocumentInformationIsExist() == false) {
            return null;
        }
        String applicantId = finishVerification.getApplicantId();

        // カード読取情報
        EkycIdDocumentInformationResponseDto idDocumentInformation = data.getIdDocumentInformation();

        String receptionNumber = finishVerification.getReceptionNumber();

        String personType = finishVerification.getPersonType();

        String updateType = finishVerification.getUpdateType();

        // 画面ID
        String screenId = RequestHeaderContext.getInstance().getScreenId();

        // Ekycの書類コードがBFFの書類コードに変換すること
        String ekycIdDocumentType = idDocumentInformation.getIdDocumentType();

        String bffIdDocumentType = EkycIdDocumentTypeCode
                .convertToBffDocumentCodeFromEkycDocumentCode(ekycIdDocumentType);

        // 人格区分が個人の場合
        if (StringUtils.equals(personType, PersonalityFlagCode.INDIVIDUAL)) {
            logger.info(applicantId + " " + "ABAに登録 (個人) start");
            // 口座開設申込内容登録（個人）ABAリクエストDTO
            AccountOpenAppliContentRegistrationIndividualAbaRequestDto abaRequestDto = new AccountOpenAppliContentRegistrationIndividualAbaRequestDto();
            // TODO eKYC 姓と名の間のスペース有無はICカードの読取情報に依存する 名前を分割する
            ParsedName parsedName = NameParser.parseJapaneseName(idDocumentInformation.getName());

            // 氏名漢字（姓）設定
            abaRequestDto.setCustomerKanjiLastName(parsedName.lastName);
            // 氏名漢字（名）設定
            abaRequestDto.setCustomerKanjiFirstName(parsedName.firstName);
            // 生年月日設定
            abaRequestDto.setBirthday(finishVerification.getBirthday());
            // 郵便番号
            abaRequestDto.setZipCode(idDocumentInformation.getZipCode());
            // 画面ID
            abaRequestDto.setPageId(screenId);
            // TODO eKYC確認結果
            abaRequestDto.setEkycResult(APP_EKYC_CHECKED);
            // abaRequestDto.setEkycResult(idDocumentInformation.getEkycResult());
            // 本人確認書類リスト設定
            abaRequestDto.setIdentityDocuments(list);
            // 本人確認書類コード
            abaRequestDto.setIdentificationDocumentCode(bffIdDocumentType);
            // 申込ステータス:(申込開始)
            abaRequestDto.setApplyStatus(ApplyStatusCode.APPLICATION_START);
            // 口座開設申込内容登録（個人）
            IndividualAbaResponseDto abaResponseDto = accountOpeningLogic
                    .accountOpenAppliIndividualContentRegistration(abaRequestDto);
            // 受付番号
            receptionNumber = abaResponseDto.getReceiptNumber();
            logger.info(applicantId + " " + "ABAに登録 (個人) end");

        } else if (StringUtils.equals(personType, PersonalityFlagCode.CORPORATE)) {
            logger.info(applicantId + " " + "ABAに登録 (法人) start");
            // 口座開設申込内容更新（法人）ABAリクエストDTO
            AccountOpenAppliContentUpdateCorporationAbaRequestDto abaRequestDto = new AccountOpenAppliContentUpdateCorporationAbaRequestDto();
            // 受付番号設定
            abaRequestDto.setReceiptNumber(receptionNumber);
            if (updateType.equals(UPDATE_TYPE_PERSONAL_013)) {
                // 代表者本人確認書類リスト
                abaRequestDto.setCeoIdentityDocuments(list);
            } else if (updateType.equals(UPDATE_TYPE_BUSINESS_017)) {
                // 事業先本人確認書類リスト
                abaRequestDto.setBusinessDocuments(list);
            }
            // 画面ID
            abaRequestDto.setPageId(screenId);
            // eKYC確認結果
            // abaRequestDto.setEkycResult(requestDto.getEkycResult());
            // TODO eKYC SDK から判定できること。
            abaRequestDto.setEkycResult(APP_EKYC_CHECKED);
            // 更新パターン
            abaRequestDto.setUpdateType(updateType);
            // 口座開設申込内容登録（法人）
            accountOpeningLogic.accountOpenAppliContentCorporationUpdate(abaRequestDto);
            logger.info(applicantId + " " + "ABAに登録 (法人) end");

        } else if (StringUtils.equals(personType, PersonalityFlagCode.ONE_PERSON_OPERATION_PERSON)) {
            logger.info(applicantId + " " + "ABAに登録 (個人事業主) start");
            // 口座開設申込情報（個人事業主）ABAリクエストDTO
            AccountOpenAppliContentUpdateSelfempAbaRequestDto abaRequestDto = new AccountOpenAppliContentUpdateSelfempAbaRequestDto();
            // 受付番号設定
            abaRequestDto.setReceiptNumber(receptionNumber);
            // TODO eKYC確認結果
            abaRequestDto.setEkycResult(APP_EKYC_CHECKED);
            // 本人確認書類リスト設定
            abaRequestDto.setCeoIdentityDocuments(list);
            // 画面ID
            abaRequestDto.setPageId(screenId);
            // 更新パターン
            abaRequestDto.setUpdateType(updateType);
            // 口座開設申込内容登録（個人事業主）
            accountOpeningLogic.accountOpenAppliContentSelfempUpdate(abaRequestDto);
            logger.info(applicantId + " " + "ABAに登録 (個人事業主) end");
        } else if (StringUtils.isEmpty(personType)) {
            logger.info(applicantId + " " + "ABAに登録 (キャッシュカード発行) start");
            // CC発行申込内容登録リクエストDTO
            CcIssuingApplicationContentRegistrationAbaRequestDto abaRequestDto = new CcIssuingApplicationContentRegistrationAbaRequestDto();

            // 画面ID設定
            abaRequestDto.setPageId(screenId);
            // 申込ステータス:(申込開始)
            abaRequestDto.setApplyStatus(ApplyStatusCode.APPLICATION_START);
            // キャッシュカード種類
            abaRequestDto.setCashCardType("0");
            // 本人確認書類リスト設定
            abaRequestDto.setIdentityDocuments(list);
            // TODO eKYC確認結果 確認
            abaRequestDto.setEkycResult(APP_EKYC_CHECKED);
            // CC発行申込内容登録リクエストDTO
            CcIssuingApplicationContentRegistrationAbaResponseDto abaResponseDto = cashcardIssueLogic
                    .contractMmanagementOperationHistoryRegistration(abaRequestDto);
            receptionNumber = abaResponseDto.getReceiptNumber();
            logger.info(applicantId + " " + "ABAに登録 (キャッシュカード発行) end");

        }
        return receptionNumber;
    }

    /**
     * ファイル名前編集
     */
    private String replaceFileName(String originalFileName, int index) {

        if (StringUtils.isBlank(originalFileName)) {
            return originalFileName;
        }

        // タイムスタンプ
        String timeStamp = String.valueOf(System.currentTimeMillis());

        String fileName = originalFileName;

        if (originalFileName.lastIndexOf("sm") == -1) {
            // 端末ID
            String deviceId = "sm"
                    + RequestHeaderContext.getInstance().getDeviceId().toLowerCase().replaceAll("-", "");
            String replacement = "-" + index + "-" + deviceId + ".jpeg";
            fileName = originalFileName.replace(".jpeg", replacement);
        }

        // タイムスタンプを入れ替わる
        fileName = fileName.replaceFirst("^(\\d+-)(\\d+(?:\\.\\d+)?)(-\\d+-)", "$1" + timeStamp + "$3");

        return fileName;

    }
}
