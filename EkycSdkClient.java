package jp.co.jsbank.mobile.bff.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import org.apache.http.HttpHost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;

import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.ssl.SSLContexts;

import jp.co.jsbank.mobile.bff.common.exception.BadRequestException;
import jp.co.jsbank.mobile.bff.common.exception.EkycRemoteApiException;
import jp.co.jsbank.mobile.bff.common.ErrorResult;
import jp.co.jsbank.mobile.bff.common.EkycApiErrorCode;
import jp.co.jsbank.mobile.bff.common.ErrorCodeConstant;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.EkycRequestInformationResponseDto;
import jp.co.jsbank.mobile.bff.dto.liquidekyc.GetTokenResponseDTO;

import java.util.Collections;

@Slf4j
public class EkycSdkClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    /**
     * @param apiKeyName
     * @param apiKeyValue
     */
    public EkycSdkClient(String apiKeyName, String apiKeyValue)  {

        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

          /*共通ヘッダー設定 */
          /* add ではなくset を利用する。add の場合、メッセージコンバータが設定済の
             Content-Type に値が追加され、ヘッダーが重複して 415 になるため。
             呼出元でContent-Typeを指定済の場合はそちらを優先する。 */
        ClientHttpRequestInterceptor apiKeyInterceptor = (request, body, execution) -> {
            request.getHeaders().set(apiKeyName, apiKeyValue);
            if (request.getHeaders().getContentType() == null) {
                request.getHeaders().set("Content-Type","application/json;charset=UTF-8");
            }
            return execution.execute(request, body);
        };

        RestTemplate tempRestTemplate = null;

         try{
            tempRestTemplate = createRestTemplate();
            tempRestTemplate.setInterceptors(Collections.singletonList(apiKeyInterceptor));

         }catch (Exception e){
            log.error("ekyc initialize restTemplate failure", e);
            throw new IllegalStateException("Failed to initialize restTemplate due to SSL error", e);
         }
         this.restTemplate = tempRestTemplate;
    }

    /**
     * EKYC  リスポンス処理
     */
    public <T> T execute(String url, HttpMethod method, Object requestBody, Class<T> responseType) {
        try {
            HttpEntity<Object> entity = new HttpEntity<>(requestBody);

            logRequestBody(entity);
            
            ResponseEntity<String> response = this.restTemplate.exchange(url, method, entity, String.class);
            HttpStatus status = (HttpStatus) response.getStatusCode();

            // 成功処理 (2xx)
            if (status.is2xxSuccessful()) {
                String body = response.getBody();
                if (responseType == Void.class || body == null || body.trim().isEmpty()) {
                    return null;
                }
                try {
                    return this.objectMapper.readValue(body, responseType);
                } catch (Exception e) {
                    log.error("ekyc リスポンス処理失敗, URL: {}", url, e);
                    EkycApiErrorCode  apiErroCode = EkycApiErrorCode.getLocaleError();
                    throw new BadRequestException(ErrorCodeConstant.EKYC_CODE_MBEK0001, "予期せぬエラー発生しました。");
                }
            }
            throw new BadRequestException(ErrorCodeConstant.EKYC_CODE_MBEK0001, "予期せぬエラー発生しました。");

        } catch (HttpStatusCodeException ex) {
            // 失败处理 (4xx/5xx) error_code
            int statusCode = ex.getStatusCode().value();
            String errorBody = ex.getResponseBodyAsString();
            
            String errorCode = String.valueOf(statusCode);
            String errorMessage = "システムエラー発生しました";

            try {
                if (errorBody != null && !errorBody.trim().isEmpty()) {
                    ErrorResult errorDto = this.objectMapper.readValue(errorBody, ErrorResult.class);
                    if (errorDto != null) {
                        errorCode = errorDto.getErrorCode();
                        errorMessage = errorDto.getErrorMessage();
                    }
                }
            } catch (Exception parseEx) {
                errorMessage = "Ekyc :リスポンスエラー詳細情報処理失敗 " + ex.getMessage();
            }
            
            EkycApiErrorCode  apiErroCode = EkycApiErrorCode.fromRawCode(errorCode);
            throw new EkycRemoteApiException(statusCode, apiErroCode, errorCode, errorMessage);
        }catch (Exception e) {
                log.error("NETWORK異常発生しました, URL: {}", url, e);
                EkycApiErrorCode  apiErroCode = EkycApiErrorCode.getSystemError();
                throw new EkycRemoteApiException(HttpStatus.INTERNAL_SERVER_ERROR.value(), apiErroCode, "SDK_NETWORK_TIMEOUT", "NETWORK異常発生しました");
        }
    }

    /**
     * EKYC  リスポンス処理
     */
    public void executePost(String url, HttpMethod method, Object requestBody, HttpHeaders requestHeaders) {
        try {
            HttpEntity<Object> entity = new HttpEntity<Object>(requestBody, requestHeaders);

            logRequestBody(entity);
            
            this.restTemplate.exchange(url, method, entity, Void.class);
            //HttpStatus status = (HttpStatus) response.getStatusCode();

            // 成功処理 (2xx)
            // if (status.is2xxSuccessful()) {
            //     String body = response.getBody();
            //     if (responseType == Void.class || body == null || body.trim().isEmpty()) {
            //         return null;
            //     }
            //     try {
            //         return this.objectMapper.readValue(body, responseType);
            //     } catch (Exception e) {
            //         log.error("ekyc リスポンス処理失敗, URL: {}", url, e);
            //         EkycApiErrorCode  apiErroCode = EkycApiErrorCode.getLocaleError();
            //         throw new BadRequestException(ErrorCodeConstant.EKYC_CODE_MBEK0001, "予期せぬエラー発生しました。");
            //     }
            // }
            // throw new BadRequestException(ErrorCodeConstant.EKYC_CODE_MBEK0001, "予期せぬエラー発生しました。");

        } catch (HttpStatusCodeException ex) {
            // 失败处理 (4xx/5xx) error_code
            int statusCode = ex.getStatusCode().value();
            String errorBody = ex.getResponseBodyAsString();
            
            String errorCode = String.valueOf(statusCode);
            String errorMessage = "システムエラー発生しました";

            try {
                if (errorBody != null && !errorBody.trim().isEmpty()) {
                    ErrorResult errorDto = this.objectMapper.readValue(errorBody, ErrorResult.class);
                    if (errorDto != null) {
                        errorCode = errorDto.getErrorCode();
                        errorMessage = errorDto.getErrorMessage();
                    }
                }
            } catch (Exception parseEx) {
                errorMessage = "Ekyc :リスポンスエラー詳細情報処理失敗 " + ex.getMessage();
            }
            
            EkycApiErrorCode  apiErroCode = EkycApiErrorCode.fromRawCode(errorCode);
            throw new EkycRemoteApiException(statusCode, apiErroCode, errorCode, errorMessage);
        }catch (Exception e) {
                log.error("NETWORK異常発生しました, URL: {}", url, e);
                EkycApiErrorCode  apiErroCode = EkycApiErrorCode.getSystemError();
                throw new EkycRemoteApiException(HttpStatus.INTERNAL_SERVER_ERROR.value(), apiErroCode, "SDK_NETWORK_TIMEOUT", "NETWORK異常発生しました");
        }
    }

    /**
     * トークン取得 API 専用 リスポンス処理
     */
    public GetTokenResponseDTO executeGetToken(String url, HttpMethod method, Object requestBody,
            HttpHeaders requestHeaders) {
        try {
            HttpEntity<Object> entity = new HttpEntity<Object>(requestBody, requestHeaders);

            logRequestBody(entity);

            ResponseEntity<GetTokenResponseDTO> response = this.restTemplate.exchange(url, method, entity,
                    GetTokenResponseDTO.class);

            return response.getBody();

        } catch (HttpStatusCodeException ex) {
            // 失败处理 (4xx/5xx) error_code
            throw toRemoteApiException(ex);
        } catch (BadRequestException | EkycRemoteApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("NETWORK異常発生しました, URL: {}", url, e);
            EkycApiErrorCode apiErroCode = EkycApiErrorCode.getSystemError();
            throw new EkycRemoteApiException(HttpStatus.INTERNAL_SERVER_ERROR.value(), apiErroCode,
                    "SDK_NETWORK_TIMEOUT", "NETWORK異常発生しました");
        }
    }

    /**
     * 申請情報登録 API 専用 リスポンス処理
     */
    public EkycRequestInformationResponseDto executeRequestInformation(String url, HttpMethod method,
            Object requestBody, HttpHeaders requestHeaders) {
        try {
            HttpEntity<Object> entity = new HttpEntity<Object>(requestBody, requestHeaders);

            logRequestBody(entity);

            ResponseEntity<EkycRequestInformationResponseDto> response = this.restTemplate.exchange(url, method, entity,
                    EkycRequestInformationResponseDto.class);

            return response.getBody();

        } catch (HttpStatusCodeException ex) {
            // 失败处理 (4xx/5xx) error_code
            throw toRemoteApiException(ex);
        } catch (BadRequestException | EkycRemoteApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("NETWORK異常発生しました, URL: {}", url, e);
            EkycApiErrorCode apiErroCode = EkycApiErrorCode.getSystemError();
            throw new EkycRemoteApiException(HttpStatus.INTERNAL_SERVER_ERROR.value(), apiErroCode,
                    "SDK_NETWORK_TIMEOUT", "NETWORK異常発生しました");
        }
    }

    /**
     * 4xx/5xx リスポンスのエラー詳細を解析し、EkycRemoteApiException に変換する
     */
    private EkycRemoteApiException toRemoteApiException(HttpStatusCodeException ex) {

        int statusCode = ex.getStatusCode().value();
        String errorBody = ex.getResponseBodyAsString();

        String errorCode = String.valueOf(statusCode);
        String errorMessage = "システムエラー発生しました";

        try {
            if (errorBody != null && !errorBody.trim().isEmpty()) {
                ErrorResult errorDto = this.objectMapper.readValue(errorBody, ErrorResult.class);
                if (errorDto != null) {
                    errorCode = errorDto.getErrorCode();
                    errorMessage = errorDto.getErrorMessage();
                }
            }
        } catch (Exception parseEx) {
            errorMessage = "Ekyc :リスポンスエラー詳細情報処理失敗 " + ex.getMessage();
        }

        EkycApiErrorCode apiErroCode = EkycApiErrorCode.fromRawCode(errorCode);
        return new EkycRemoteApiException(statusCode, apiErroCode, errorCode, errorMessage);
    }

    private void logRequestBody(HttpEntity<Object> entity) throws Exception {

        if (entity == null || entity.getBody() == null) {
            return;
        }
        try {
            String finalJsonStr = this.objectMapper.writeValueAsString(entity.getBody());
            log.info("ekyc final request body:" + finalJsonStr);
        } catch (Exception e) {
             log.error("ekyc read request failed, msg: ", e);
             EkycApiErrorCode  apiErroCode = EkycApiErrorCode.getLocaleError();
             throw new EkycRemoteApiException(HttpStatus.INTERNAL_SERVER_ERROR.value(), apiErroCode, "リクエスト読取失敗", "Failed to read the request body data");
        }

    }

    private RestTemplate createRestTemplate() throws Exception {
        
           TrustStrategy trustAllStrategy = (X509Certificate[] chain, String authType) -> true;

            SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(null, trustAllStrategy).build();

            SSLConnectionSocketFactory socketFactory =
                    new SSLConnectionSocketFactory(
                        sslContext,
                        new String[] { "TLSv1.2" },
                        null,
                        NoopHostnameVerifier.INSTANCE
                    );

            System.setProperty("jsse.enableSNIExtension", "false");

            // フォワードプロキシ
            HttpHost proxy = new HttpHost(

                    "dsp-access-2.d-dspcommon.internal",//開発環境
    //					"dsp-access-2.p-dspcommon.internal", //本番環境
                    1052);

            CloseableHttpClient httpClient = HttpClients.custom().setSSLSocketFactory(socketFactory).setProxy(proxy)
                    .build();

            HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);

            factory.setConnectTimeout(10_000);
            factory.setReadTimeout(10 * 60_000);

            return new RestTemplate(factory);
    }
}