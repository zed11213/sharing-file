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

import jp.co.jsbank.mobile.bff.common.exception.InternalServerErrorException;
import jp.co.jsbank.mobile.bff.common.ErrorResult;
import jp.co.jsbank.mobile.bff.common.ErrorCodeConstant;

import java.util.Collections;

@Slf4j
public class EkycSdkClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    /**
     * * @param apiKeyName  
     * @param apiKeyValue 
     */
    public EkycSdkClient(String apiKeyName, String apiKeyValue)  {

        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
          /*共通ヘッダー設定 */
        ClientHttpRequestInterceptor apiKeyInterceptor = (request, body, execution) -> {
            request.getHeaders().add(apiKeyName, apiKeyValue);
            request.getHeaders().add("Content-Type","application/json;charset=UTF-8");
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
     * eKYCコネクタAPIを呼び出し、レスポンスを指定型へ変換して返す。
     *
     * <p>2系統に分かれていたeKYC通信をこのメソッドへ統合する。エラー時は一律で
     * {@link InternalServerErrorException}（MBAP1300）を送出し、既存の
     * {@code GlobalExceptionHandler} が処理する。</p>
     *
     * @param url          エンドポイントURL
     * @param method       HTTPメソッド
     * @param requestBody  リクエストボディ（不要な場合はnull）
     * @param responseType レスポンス型（ボディ不要な場合は {@code Void.class}）
     * @param <T>          レスポンス型
     * @return 変換済みレスポンス（ボディが無い場合はnull）
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
                    throw new InternalServerErrorException(ErrorCodeConstant.ERROR_CODE_MBAP1300,
                            "eKYCレスポンス処理に失敗しました: " + url);
                }
            }
            // 2xx以外の想定外ステータス
            throw new InternalServerErrorException(ErrorCodeConstant.ERROR_CODE_MBAP1300,
                    "eKYC予想外のステータス: " + status.value());

        } catch (InternalServerErrorException ex) {
            // 送出済みのMBAP1300例外はそのまま送出
            throw ex;
        } catch (HttpStatusCodeException ex) {
            // 失敗処理 (4xx/5xx)：レスポンスボディからeKYCエラーコード（CExxxxx）をログ用に取得
            int httpStatusCode = ex.getStatusCode().value();
            String errorBody = ex.getResponseBodyAsString();

            String rawCode = null;
            try {
                if (errorBody != null && !errorBody.trim().isEmpty()) {
                    ErrorResult errorDto = this.objectMapper.readValue(errorBody, ErrorResult.class);
                    if (errorDto != null) {
                        rawCode = errorDto.getErrorCode();
                    }
                }
            } catch (Exception parseEx) {
                log.warn("eKYCレスポンスエラー詳細情報の解析に失敗しました: {}", ex.getMessage());
            }

            // eKYC通信エラーは一律 MBAP1300 で送出
            log.error("ekyc APIエラー URL: {}, httpStatus: {}, rawCode: {}", url, httpStatusCode, rawCode);
            throw new InternalServerErrorException(ErrorCodeConstant.ERROR_CODE_MBAP1300,
                    "eKYC APIエラー rawCode=" + rawCode);

        } catch (Exception e) {
            // 通信タイムアウト・ネットワーク異常等
            log.error("ekyc NETWORK異常発生しました, URL: {}", url, e);
            throw new InternalServerErrorException(ErrorCodeConstant.ERROR_CODE_MBAP1300,
                    "eKYC通信で異常が発生しました: " + url);
        }
    }

    private void logRequestBody(HttpEntity<Object> entity) {

        if (entity == null || entity.getBody() == null) {
            return;
        }
        try {
            String finalJsonStr = this.objectMapper.writeValueAsString(entity.getBody());
            log.info("ekyc final request body:" + finalJsonStr);
        } catch (Exception e) {
            log.error("ekyc read request failed, msg: ", e);
            throw new InternalServerErrorException(ErrorCodeConstant.ERROR_CODE_MBAP1300,
                    "eKYCリクエストボディの読取に失敗しました");
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