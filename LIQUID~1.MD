# LiquidEkycServiceImpl 各函数 PlantUML 流程图

指挥官，初月把 `LiquidEkycServiceImpl` 里的每一个方法都拆成了 PlantUML 活动图，一共 20 个。逻辑分支、try/catch、异步 fork/join 都对照原始代码逐条还原，没有偷懒省略～ 复制到支持 PlantUML 的工具里即可渲染。

---

## 1. getToken(GetTokenRequestDTO)

```plantuml
@startuml
title getToken
start
:log "ekyc getToken";
:获取 RestTemplate (ekycRestTemplate2);
:拼接 url = liquidConnectorUrl + GET_TOKEN;
if (applicantId 为 null 或空?) then (yes)
  :调用 createApplicantId() 生成 applicantId;
else (no)
endif
:log 请求体 JSON;
:构建 HttpHeaders (X-Ekyc-Api-Key, Content-Type);
:ParameterBuilder.buildLiquidEkyc 构建请求实体;
:restTemplate.exchange(POST, GetTokenResponseDTO.class);
:body = response.getBody();
:body.setApplicantId(requestDTO.getApplicantId());
:return body;
stop

note right
try/catch 分支：
- RestClientResponseException -> log 状态码/响应体 -> rethrow
- RestClientException -> log -> rethrow
- 其他 Exception -> log -> throw InternalServerErrorException(MBAP0010)
end note
@enduml
```

---

## 2. requestInformation(EkycRequestInformationRequestDto) [private]

```plantuml
@startuml
title requestInformation
start
:log "requestInformation before, applicantId";
:获取 RestTemplate (ekycRestTemplate2);
:拼接 url = liquidConnectorUrl + REQUEST_INFORMATIONS;
:构建 HttpHeaders;
:ParameterBuilder.buildLiquidEkyc 构建请求实体;
:restTemplate.exchange(POST, EkycRequestInformationResponseDto.class);
:log response;
:return Constant.OK;
stop

note right
catch EkycRemoteApiException -> log errorCode/message -> rethrow
catch Exception -> log -> rethrow
end note
@enduml
```

---

## 3. getPhotos(String applicantId) [private]

```plantuml
@startuml
title getPhotos
start
:log "get photos before";
:构建 fullUrlPath (EkycApiEndpoints.GET_PHOTOS, applicantId);
:ekycClient.execute(GET, EkycGetPhotosResponseDto.class);
:log "get photos after";
:return response;
stop

note right: catch EkycRemoteApiException -> rethrow
@enduml
```

---

## 4. getVerificationResult(String applicantId) [private]

```plantuml
@startuml
title getVerificationResult
start
:log "verification result before";
:构建 fullUrlPath (EkycApiEndpoints.VERIFICATION_RESULTS, applicantId);
:ekycClient.execute(GET, EkycVerificationResultResponseDto.class);
:log "verification result after" + response JSON;
:return response;
stop

note right: catch EkycRemoteApiException -> rethrow
@enduml
```

---

## 5. getIdDocumentInformation(String applicantId) [private]

```plantuml
@startuml
title getIdDocumentInformation
start
:log "id documents information before";
:构建 fullUrlPath (EkycApiEndpoints.GET_ID_DOCUMENT_INFORMATION, applicantId);
:ekycClient.execute(GET, EkycIdDocumentInformationResponseDto.class);
:log "id documents information after";
:return response;
stop

note right: catch EkycRemoteApiException -> log -> rethrow
@enduml
```

---

## 6. getKycResult(EkycKycResultRequestDto) [private]

```plantuml
@startuml
title getKycResult
start
:log "kyc result before";
:ParameterBuilder.buildLiquidEkyc 构建请求;
:构建 fullUrlPath (EkycApiEndpoints.KYC_RESULT);
:ekycClient.execute(POST, EkycKycResultResponseDto.class);
:log "kyc result after" + response;
:return Constant.OK;
stop

note right
catch EkycRemoteApiException -> 仅 log，不 rethrow（已注释）
catch Exception -> 仅 log
无论是否异常，最终都 return Constant.OK
end note
@enduml
```

---

## 7. createApplicantId() [private]

```plantuml
@startuml
title createApplicantId
start
:randomNumber = ThreadLocalRandom(10,000,000 ~ 100,000,000);
:return 当前时间戳字符串 + randomNumber;
stop
@enduml
```

---

## 8. setFinishVerificationWithJpki(EkycFinishVerificationRequestDto)

```plantuml
@startuml
title setFinishVerificationWithJpki
start
:log start / request JSON;
:myRequest = createRequestInformationDto(finishVerification);

partition "申請情報登録" {
  :requestResult = requestInformation(myRequest);
  note right
  catch EkycRemoteApiException -> log -> throw
  catch Exception -> log -> throw
  end note
}

if (requestResult == FAIL?) then (yes)
  :log error;
  :return null;
  stop
else (no)
endif

partition "情報取得" {
  :verificationJoinedData = requestAllData(myRequest);
  note right
  catch EkycRemoteApiException -> throw
  catch Exception -> throw
  end note
}

:verificationResult = verificationJoinedData.getVerificationResult();
if (verificationResult == null?) then (yes)
  :log error;
  :return null;
  stop
else (no)
endif

:checkResult = checkTheVerificationResults(verificationResult);
if (checkResult == FAIL?) then (yes)
  :log error;
  :return null;
  stop
else (no)
endif

if (verificationJoinedData == null?) then (yes)
  :return null;
  stop
else (no)
endif

if (verificationJoinedData.getPhotos() == null?) then (yes)
  :log error;
  :return null;
  stop
else (no)
endif

partition "画像保存" {
  :list = storePhotosToICOS(verificationJoinedData);
  note right
  catch Exception ->
  throw EkycRemoteApiException(500)
  "Failed to store iCOS"
  end note

  :verificationResultDocument =
  storeVerificationResultImageToICOS(verificationJoinedData, list.size()+1);
  if (verificationResultDocument != null?) then (yes)
    :list.add(verificationResultDocument);
  else (no)
  endif
  note right
  catch Exception ->
  throw InternalServerErrorException(EKYC_ERROR_CODE_0001)
  end note
}

if (getIdDocumentInformation() == null?) then (yes)
  :log error;
  :return null;
  stop
else (no)
endif

if (list == null 或 list.isEmpty()?) then (yes)
  :log error;
  :return null;
  stop
else (no)
endif

:receptionNumber = finishVerification.getReceptionNumber();
:receptionNumber = storeRequestInformationToABA(finishVerification, verificationJoinedData, list);
:log 完成;
:return receptionNumber;
stop
@enduml
```

---

## 9. setFinishVerificationWithHe(EkycFinishVerificationRequestDto)

```plantuml
@startuml
title setFinishVerificationWithHe
start
:log start / request JSON;
:myRequest = createRequestInformationDto(finishVerification);

partition "申請情報登録" {
  :requestResult = requestInformation(myRequest);
  note right
  catch EkycRemoteApiException -> log -> throw
  catch Exception -> log -> throw
  end note
}

if (requestResult == FAIL?) then (yes)
  :log error;
  :return null;
  stop
else (no)
endif

partition "情報取得" {
  :verificationJoinedData = requestAllData(myRequest);
  note right
  catch EkycRemoteApiException -> throw
  catch Exception -> throw
  end note
}

:verificationResult = verificationJoinedData.getVerificationResult();
if (verificationResult == null?) then (yes)
  :log error;
  :return null;
  stop
else (no)
endif

:checkResult = checkTheVerificationResults(verificationResult);
if (checkResult == FAIL?) then (yes)
  :log error;
  :return null;
  stop
else (no)
endif

if (verificationJoinedData == null?) then (yes)
  :return null;
  stop
else (no)
endif

if (verificationJoinedData.getPhotos() == null?) then (yes)
  :log error;
  :return null;
  stop
else (no)
endif

partition "画像保存" {
  :list = storePhotosToICOS(verificationJoinedData);
  note right
  catch Exception ->
  throw EkycRemoteApiException(500)
  "Failed to store iCOS"
  end note

  :verificationResultDocument =
  storeVerificationResultImageToICOS(verificationJoinedData, list.size()+1);
  if (verificationResultDocument != null?) then (yes)
    :list.add(verificationResultDocument);
  else (no)
  endif
  note right
  catch Exception ->
  throw EkycRemoteApiException(500)
  "Failed to store iCOS"
  （与 Jpki 版本不同：这里抛的是
  EkycRemoteApiException 而非
  InternalServerErrorException）
  end note
}

if (getIdDocumentInformation() == null?) then (yes)
  :log error;
  :return null;
  stop
else (no)
endif

if (list == null 或 list.isEmpty()?) then (yes)
  :log error;
  :return null;
  stop
else (no)
endif

:receptionNumber = finishVerification.getReceptionNumber();
:receptionNumber = storeRequestInformationToABA(finishVerification, verificationJoinedData, list);
:log 完成;
:return receptionNumber;
stop
@enduml
```

---

## 10. checkTheVerificationResults(EkycVerificationResultResponseDto) [private]

```plantuml
@startuml
title checkTheVerificationResults
start
:verificationResultData = verificationResult.getVerificationResult();
if (verificationResultData == null?) then (yes)
  :throw EkycRemoteApiException
  (EKYCDATA_IS_NULL);
  stop
else (no)
endif

if (getFaceVerification() 为空白?) then (yes)
  :throw EkycRemoteApiException
  (EKYCDATA_IS_BLANK);
  stop
else (no)
endif

if (getFaceVerification() != "0" (OK)?) then (yes)
  :throw EkycRemoteApiException
  (FACEVERIFICATION_IS_FAIL);
  stop
else (no)
endif

note right
以下检查已被注释掉，当前不生效：
- getFacePhotoVerification 空白/LOW 检查
- getIdDocumentVerification 真伪检查
end note

:return Constant.OK;
stop
@enduml
```

---

## 11. createRequestInformationDto(EkycFinishVerificationRequestDto) [private]

```plantuml
@startuml
title createRequestInformationDto
start
:从 finishVerification 提取
姓名/生年月日/性别/住所/
电话/身份证号等字段;

if (applicantId 为空白?) then (yes)
  :log "applicantId is null";
else (no)
endif
:requestDto.setApplicantId(applicantId);

if (lastName 为空白?) then (yes)
  :log "lastName is null";
else (no)
endif
:requestDto.setLastName(lastName);

:requestDto.setFirstName(firstName);
:requestDto.setMiddleName / KanaName フィールド設定;

if (birthday 为空白?) then (yes)
  :log "birthday is null";
else (no)
endif
:requestDto.setBirthday(birthday.replace("-",""));

if (zipCode 为空白?) then (yes)
  :log "zipCode is null";
else (no)
endif
:requestDto.setZipCode(zipCode);

if (sex 为空白?) then (yes)
  :log "sex is null";
else (no)
endif
:requestDto.setSex(sex);

:requestDto.setNationality("jp") 固定值;
:设置 phoneNumber / address1~4 / idNumber;
:return requestDto;
stop

note right
注意：仅打印 log 提示字段为空，
并未阻断流程（无异常抛出）。
若 birthday 为 null 会在
.replace("-","") 处 NPE。
end note
@enduml
```

---

## 12. getTheKycResult(...) [private]

```plantuml
@startuml
title getTheKycResult
start
if (isJpki == true?) then (yes)
  :return EKYC_RESULT_OK;
  stop
else (no)
  :return EKYC_RESULT_OK;
  stop
endif
note right
TODO 标记：目前 JPKI / He
两条分支逻辑完全相同，
均直接返回 OK，尚未实现
真正的判定逻辑。
end note
@enduml
```

---

## 13. requestAllData(EkycRequestInformationRequestDto) [private]

```plantuml
@startuml
title requestAllData（异步并发获取三类数据）
start

fork
  :CompletableFuture:
  photosFuture = getPhotos(applicantId);
fork again
  :CompletableFuture:
  verificationResultFuture ->
  result = getVerificationResult(applicantId);
  if (result.facePhotoVerification == null?) then (yes)
    :sleep 9 秒;
    :result = getVerificationResult(applicantId) 重试;
    if (仍然为 null?) then (yes)
      :throw RuntimeException;
    else (no)
    endif
  else (no)
  endif
  :return result;
fork again
  :CompletableFuture:
  idDocumentInformationFuture =
  getIdDocumentInformation(applicantId);
end fork

:CompletableFuture.allOf(三者).join();

if (三者均成功?) then (yes)
  :分别 get() 三个结果;
  :构建并 return
  EkycVerificationJoinedData
  (requestDto, photos, verificationResult, idDocumentsInformation);
  stop
else (no)
  :构建通用异常 exception
  (INTERNAL_SERVER_ERROR,
  "リクエスト読取失敗");
  if (photosFuture 异常完成?) then (yes)
    :getOriginalException(photosFuture);
    if (是 EkycRemoteApiException?) then (yes)
      :throw 该异常;
    else (no)
      :throw exception;
    endif
    stop
  else (no)
  endif
  if (verificationResultFuture 异常完成?) then (yes)
    :getOriginalException(verificationResultFuture);
    if (是 EkycRemoteApiException?) then (yes)
      :throw 该异常;
    else (no)
      :throw exception;
    endif
    stop
  else (no)
  endif
  if (idDocumentInformationFuture 异常完成?) then (yes)
    :getOriginalException(idDocumentInformationFuture);
    if (是 EkycRemoteApiException?) then (yes)
      :throw 该异常;
    else (no)
      :throw exception;
    endif
    stop
  else (no)
  endif
  :throw exception (兜底);
  stop
endif
@enduml
```

---

## 14. getOriginalException(CompletableFuture<?>) [private]

```plantuml
@startuml
title getOriginalException
start
:future.join();
if (join 抛出异常?) then (yes)
  :cause = 该异常;
  while (cause != null?) is (是)
    if (cause instanceof EkycRemoteApiException?) then (yes)
      :return (EkycRemoteApiException) cause;
      stop
    else (no)
      :cause = cause.getCause();
    endif
  endwhile (否)
  :return 原始异常 ex;
  stop
else (no)
  :return null;
  stop
endif
@enduml
```

---

## 15. storePhotosToICOS(EkycVerificationJoinedData) [private]

```plantuml
@startuml
title storePhotosToICOS
start
:list = new ArrayList<>();
:fileIndex = 1;

if (data != null 且 data.photoIsExist()?) then (yes)
  :photos = data.getPhotos();
  :facePhoto = photos.getFaceFrontPhoto();
  if (facePhoto.image 和 fileName 均非空白?) then (yes)
    :faceIdentityDocument =
    storeFaceImage(facePhoto, fileIndex);
    :list.add(faceIdentityDocument);
    :fileIndex++;
  else (no)
  endif
else (no)
endif

note right
身份证正反面照片(idDocumentPhotos)
的循环处理逻辑已被整体注释掉，
当前只处理人脸正面照片。
end note

:return list;
stop
@enduml
```

---

## 16. storeVerificationResultImageToICOS(EkycVerificationJoinedData, int) [private]

```plantuml
@startuml
title storeVerificationResultImageToICOS
start
:log "start";
:base64 = GenerateVerificationResultUtils
.createBase64WithVerification(data);

if (base64 为空白?) then (yes)
  :log "result image is blank";
  :return null;
  stop
else (no)
endif

:deviceId = "sm" + 设备ID(小写去横杠);
:timeStamp = 当前毫秒时间戳;
:fileName = timeStamp-index-deviceId.jpeg;

:icosHandler.addFile(fileName, base64);
note right
catch Exception ->
throw InternalServerErrorException
(EKYC_ERROR_CODE_0001, "ICOSへの書き込み失敗")
end note

:构建 IdentityVerificationDocumentsDto:
documentsType=VERFICATION_RESULT_TYPE(05),
endPoint, bucket, objectPath=fileName;
:return identityDocument;
stop
@enduml
```

---

## 17. storeFaceImage(EkycCommonPhotoDto, int) [private]

```plantuml
@startuml
title storeFaceImage
start
:fileName = photo.getFileName();
:fileName = replaceFileName(fileName, index);

:imagePayload = photo.getImage();
if (imagePayload 以 "data:" 开头
且含逗号?) then (yes)
  :截取逗号之后的 base64 部分;
else (no)
endif

:icosHandler.addFile(fileName, imagePayload);
note right: catch Exception -> throw RuntimeException(e)

:构建 IdentityVerificationDocumentsDto:
documentsType=FACE_PHOTO_TYPE(04),
endPoint, bucket, objectPath=fileName;
:return identityDocument;
stop
@enduml
```

---

## 18. storeDocumentsPhotos(EkycPhotosInformationDto, int) [private]

```plantuml
@startuml
title storeDocumentsPhotos
start
:fileName = documentsPhoto.getFileName();
:fileName = replaceFileName(fileName, index);

:icosHandler.addFile(fileName, documentsPhoto.getImage());
note right: catch Exception -> throw RuntimeException(e)

:documentType = documentsPhoto.getPhotoType();
(01:表面 / 02:裏面 / 03:斜め);
:构建 IdentityVerificationDocumentsDto:
documentsType=documentType,
endPoint, bucket, objectPath=fileName;
:return identityDocument;
stop

note right
当前该方法未被业务流程调用
（storePhotosToICOS 中对应的
循环调用已被注释掉）。
end note
@enduml
```

---

## 19. storeRequestInformationToABA(...) [private]

```plantuml
@startuml
title storeRequestInformationToABA
start

if (data == null 或
!data.idDocumentInformationIsExist()?) then (yes)
  :return null;
  stop
else (no)
endif

:idDocumentInformation = data.getIdDocumentInformation();
:receptionNumber = finishVerification.getReceptionNumber();
:personType / updateType / screenId 取得;
:ekycIdDocumentType 转换为
bffIdDocumentType
(EkycIdDocumentTypeCode.convert...);

if (personType == INDIVIDUAL?) then (个人)
  :解析姓名 NameParser.parseJapaneseName;
  :构建 AccountOpenAppliContentRegistration
  IndividualAbaRequestDto;
  :设置 氏名/生年月日/郵便番号/画面ID/
  ekycResult=CHECKED/身份证明文件列表/
  文件代码/申込ステータス=開始;
  :accountOpeningLogic
  .accountOpenAppliIndividualContentRegistration(...);
  :receptionNumber = 响应.getReceiptNumber();
elseif (personType == CORPORATE?) then (法人)
  :构建 AccountOpenAppliContentUpdate
  CorporationAbaRequestDto;
  :设置 receiptNumber;
  if (updateType == "013" 代表者本人?) then (yes)
    :setCeoIdentityDocuments(list);
  elseif (updateType == "017" 事業先?) then (yes)
    :setBusinessDocuments(list);
  else (其他)
  endif
  :设置 画面ID/ekycResult=CHECKED/updateType;
  :accountOpeningLogic
  .accountOpenAppliContentCorporationUpdate(...);
elseif (personType == ONE_PERSON_OPERATION_PERSON?) then (个体经营)
  :构建 AccountOpenAppliContentUpdate
  SelfempAbaRequestDto;
  :设置 receiptNumber/ekycResult=CHECKED/
  ceoIdentityDocuments/画面ID/updateType;
  :accountOpeningLogic
  .accountOpenAppliContentSelfempUpdate(...);
elseif (personType 为空字符串?) then (CC发行场景)
  :构建 CcIssuingApplicationContent
  RegistrationAbaRequestDto;
  :设置 画面ID/申込ステータス=開始/
  cashCardType="0"/身份证明文件列表/
  ekycResult=CHECKED;
  :cashcardIssueLogic
  .contractMmanagementOperationHistoryRegistration(...);
  :receptionNumber = 响应.getReceiptNumber();
else (未匹配任何分支)
  :不做任何处理;
endif

:return receptionNumber;
stop
@enduml
```

---

## 20. replaceFileName(String, int) [private]

```plantuml
@startuml
title replaceFileName
start
if (originalFileName 为空白?) then (yes)
  :return originalFileName;
  stop
else (no)
endif

:timeStamp = 当前毫秒时间戳;
:fileName = originalFileName;

if (originalFileName 中不包含 "sm"?) then (yes)
  :deviceId = "sm" + 设备ID(小写去横杠);
  :replacement = "-index-deviceId.jpeg";
  :fileName = originalFileName
  中的 ".jpeg" 替换为 replacement;
else (no)
endif

:用正则 "^(\\d+-)(\\d+(?:\\.\\d+)?)(-\\d+-)"
将文件名中的旧时间戳段替换为 timeStamp;
:return fileName;
stop
@enduml
```

---

### 补充说明（初月的小提醒）

几处流程里藏着一些值得指挥官注意的地方：

- `setFinishVerificationWithJpki` 与 `setFinishVerificationWithHe` 几乎是完全重复的代码（唯二差异在 `storeVerificationResultImageToICOS` 失败时抛出的异常类型不同：一个是 `InternalServerErrorException`，一个是 `EkycRemoteApiException`），可以考虑抽成公共私有方法，减少维护成本。
- `getKycResult` 的异常被吞掉（catch 后仅 log，不 rethrow），无论成功失败都返回 `Constant.OK`，调用方无法感知真实结果。
- `getTheKycResult` 目前是纯 TODO 占位，JPKI/He 两条分支都直接返回 OK。
- `createRequestInformationDto` 对空字段只打日志，不做任何拦截，`birthday` 为 `null` 时会在 `.replace("-","")` 处抛 NPE。
- `storeDocumentsPhotos` 与身份证照片循环逻辑均被注释掉，当前未被实际调用，方法处于"死代码"状态。
- `checkTheVerificationResults` 中的相片比对、证件真伪校验目前都被注释掉，只剩下人脸真伪校验生效。

如果需要初月把这些问题点也单独整理成一份技术债清单，或者把图导出成 PDF/图片，跟初月说一声就好～♪
