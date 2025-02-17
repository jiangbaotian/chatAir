package com.theokanning.openai.service;

import android.text.TextUtils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.flyun.base.BaseMessage;
import com.theokanning.openai.DeleteResult;
import com.theokanning.openai.GoogleError;
import com.theokanning.openai.GoogleHttpException;
import com.theokanning.openai.OpenAiApi;
import com.theokanning.openai.OpenAiError;
import com.theokanning.openai.OpenAiHttpException;
import com.theokanning.openai.completion.CompletionChunk;
import com.theokanning.openai.completion.CompletionRequest;
import com.theokanning.openai.completion.CompletionResult;
import com.theokanning.openai.completion.chat.ChatCompletionChunk;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;
import com.theokanning.openai.completion.chat.ChatGCompletionRequest;
import com.theokanning.openai.completion.chat.ChatGCompletionResponse;
import com.theokanning.openai.edit.EditRequest;
import com.theokanning.openai.edit.EditResult;
import com.theokanning.openai.embedding.EmbeddingRequest;
import com.theokanning.openai.embedding.EmbeddingResult;
import com.theokanning.openai.file.File;
import com.theokanning.openai.finetune.FineTuneEvent;
import com.theokanning.openai.finetune.FineTuneRequest;
import com.theokanning.openai.finetune.FineTuneResult;
import com.theokanning.openai.image.CreateImageEditRequest;
import com.theokanning.openai.image.CreateImageRequest;
import com.theokanning.openai.image.CreateImageVariationRequest;
import com.theokanning.openai.image.ImageResult;
import com.theokanning.openai.model.Model;
import com.theokanning.openai.moderation.ModerationRequest;
import com.theokanning.openai.moderation.ModerationResult;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.Single;
import io.reactivex.SingleObserver;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.HttpException;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;

public class OpenAiService {

    private static final String BASE_URL = "https://api.openai.com/";
    private static final String BASE_GOOGLE_URL = "https://generativelanguage.googleapis.com/";
    private static final long DEFAULT_TIMEOUT = 10;
    private static final ObjectMapper mapper = defaultObjectMapper();

    private final OpenAiApi api;
    private OkHttpClient client;
    private final ExecutorService executorService;

    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private final WeakHashMap<String, Call<ResponseBody>> requestList = new WeakHashMap();

    public final int listModels = 1;

    private boolean isGoogleUrl = false;
    private boolean isGoogleToken = false;

    /**
     * Creates a new OpenAiService that wraps OpenAiApi
     *
     * @param token OpenAi token string "sk-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
     */
    public OpenAiService(final String token) {
        this(token, DEFAULT_TIMEOUT, BASE_URL, false);
    }

    /**
     * Creates a new OpenAiService that wraps OpenAiApi
     *
     * @param token  OpenAi token string "sk-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
     * @param second http read timeout
     */
    public OpenAiService(final String token, final long second, final String url, boolean isGoogle) {

        String baseUrl = (TextUtils.isEmpty(url) || HttpUrl.parse(url) == null) ? BASE_URL : url;

        ObjectMapper mapper = defaultObjectMapper();
        this.client = defaultClient(token, second, url, isGoogle);
        this.executorService = client.dispatcher().executorService();
        Retrofit retrofit = defaultRetrofit(client, mapper, baseUrl, this.executorService);

        this.api = retrofit.create(OpenAiApi.class);

        // 初始化isGoogle
        isGoogleToken = isGoogle;
        isGoogleUrl = isGoogle;
    }

    public void switchDefault(String token, String url) {

        if (isGoogleToken) {
            changeToken(token);
        }
        if (isGoogleUrl) {
            changeServer(url);
        }
    }

    public void switchGoogle(String token, String url) {

        if (!isGoogleToken) {
            changeTokenGoogle(token);
        }
        if (!isGoogleUrl) {
            changeServerGoogle(url);
        }
    }

    public void changeMatchToken(String token, String matchUrl) {

        if (!isGoogleUrl) {
            // Url为默认，不需要改变
            changeToken(token);
        } else {
            // Url为google，需要Url和token一起改变
            changeTokenAndServer(token, matchUrl);
        }
    }

    public void changeMatchServer(String url, String matchToken) {

        if (!isGoogleToken) {
            // token为默认，不需要改变
            changeServer(url);
        } else {
            // token为google，需要token和Url一起改变
            changeTokenAndServer(matchToken, url);
        }
    }


    public void changeMatchTokenGoogle(String token, String matchUrl) {

        if (isGoogleUrl) {
            // Url为Google，不需要改变
            changeTokenGoogle(token);
        } else {
            // Url为默认，需要Url和token一起改变
            changeTokenAndServerGoogle(token, matchUrl);
        }
    }

    public void changeMatchServerGoogle(String url, String matchToken) {

        if (isGoogleToken) {
            // token为Google，不需要改变
            changeServerGoogle(url);
        } else {
            // token为默认，需要token和Url一起改变
            changeTokenAndServerGoogle(matchToken, url);
        }
    }


    public void changeToken(String token) {
        if (client != null && token != null) {
            for (Interceptor interceptor : client.interceptors()) {
                if (interceptor instanceof AuthenticationInterceptor) {
                    isGoogleToken = false;
                    ((AuthenticationInterceptor) interceptor).setToken(token);
                    ((AuthenticationInterceptor) interceptor).setGoogle(isGoogle());
                }
            }
        }
    }

    public void changeServer(String url) {
        if (client != null && !TextUtils.isEmpty(url)) {
            for (Interceptor interceptor : client.interceptors()) {
                if (interceptor instanceof AuthenticationInterceptor) {
                    isGoogleUrl = false;
                    ((AuthenticationInterceptor) interceptor).setUrl(url);
                    ((AuthenticationInterceptor) interceptor).setGoogle(isGoogle());
                }
            }
        }
    }

    public void changeTokenAndServer(String token, String url) {
        if (client != null && !TextUtils.isEmpty(token) && !TextUtils.isEmpty(url)) {
            for (Interceptor interceptor : client.interceptors()) {
                if (interceptor instanceof AuthenticationInterceptor) {
                    isGoogleUrl = false;
                    ((AuthenticationInterceptor) interceptor).setToken(token);
                    ((AuthenticationInterceptor) interceptor).setUrl(url);
                    ((AuthenticationInterceptor) interceptor).setGoogle(isGoogle());
                }
            }
        }
    }

    public void changeTokenGoogle(String token) {
        if (client != null && token != null) {
            for (Interceptor interceptor : client.interceptors()) {
                if (interceptor instanceof AuthenticationInterceptor) {
                    isGoogleToken = true;
                    ((AuthenticationInterceptor) interceptor).setToken(token);
                    ((AuthenticationInterceptor) interceptor).setGoogle(isGoogle());
                }
            }
        }
    }

    public void changeServerGoogle(String url) {
        if (client != null && !TextUtils.isEmpty(url)) {
            for (Interceptor interceptor : client.interceptors()) {
                if (interceptor instanceof AuthenticationInterceptor) {
                    isGoogleUrl = true;
                    ((AuthenticationInterceptor) interceptor).setUrl(url);
                    ((AuthenticationInterceptor) interceptor).setGoogle(isGoogle());
                }
            }
        }
    }

    public void changeTokenAndServerGoogle(String token, String url) {
        if (client != null && !TextUtils.isEmpty(token)  && !TextUtils.isEmpty(url)) {
            for (Interceptor interceptor : client.interceptors()) {
                if (interceptor instanceof AuthenticationInterceptor) {
                    isGoogleUrl = false;
                    ((AuthenticationInterceptor) interceptor).setToken(token);
                    ((AuthenticationInterceptor) interceptor).setUrl(url);
                    ((AuthenticationInterceptor) interceptor).setGoogle(isGoogle());
                }
            }
        }
    }

    // url与token必须绑定
    public boolean isGoogle() {
        if (isGoogleUrl && isGoogleToken) return true;
        if (!isGoogleUrl && !isGoogleToken) return false;

        // Url与token不匹配，抛出异常
        System.out.print("URL does not match token isUrl:" + isGoogleUrl  + "isToken:" + isGoogleToken);
        return false;
    }

    /**
     * Creates a new OpenAiService that wraps OpenAiApi.
     * Use this if you need more customization, but use OpenAiService(api, executorService) if
     * you use streaming and
     * want to shut down instantly
     *
     * @param api OpenAiApi instance to use for all methods
     */
    public OpenAiService(final OpenAiApi api) {
        this.api = api;
        this.executorService = null;
    }

    /**
     * Creates a new OpenAiService that wraps OpenAiApi.
     * The ExecutorService must be the one you get from the client you created the api with
     * otherwise shutdownExecutor() won't work.
     * <p>
     * Use this if you need more customization.
     *
     * @param api             OpenAiApi instance to use for all methods
     * @param executorService the ExecutorService from client.dispatcher().executorService()
     */
    public OpenAiService(final OpenAiApi api, final ExecutorService executorService) {
        this.api = api;
        this.executorService = executorService;
    }

    public List<Model> listModels() {
        return execute(api.listModels()).data;
    }

    public Model getModel(String modelId) {
        return execute(api.getModel(modelId));
    }

    public CompletionResult createCompletion(CompletionRequest request) {
        return execute(api.createCompletion(request));
    }

    public Flowable<CompletionChunk> streamCompletion(CompletionRequest request) {
        request.setStream(true);

        return stream(api.createCompletionStream(request), CompletionChunk.class);
    }

    public ChatCompletionResult createChatCompletion(ChatCompletionRequest request) {
        return execute(api.createChatCompletion(request));
    }

    public void createChatCompletion(ChatCompletionRequest request, final BaseMessage baseMessage
            , final ResultCallBack callBack) {

        createChatCompletion(api.createChatCompletion(request), baseMessage, callBack);
    }

    public void createChatGCompletion(ChatGCompletionRequest request, String model,
                                      final BaseMessage baseMessage, final ResultGCallBack callBack) {

        createChatGCompletion(api.createGChatCompletion(model, request), baseMessage, callBack);
    }

    public Flowable<ChatCompletionChunk> streamChatCompletion(ChatCompletionRequest request) {
        request.setStream(true);

        return stream(api.createChatCompletionStream(request), ChatCompletionChunk.class);
    }

    public EditResult createEdit(EditRequest request) {
        return execute(api.createEdit(request));
    }

    public EmbeddingResult createEmbeddings(EmbeddingRequest request) {
        return execute(api.createEmbeddings(request));
    }

    public List<File> listFiles() {
        return execute(api.listFiles()).data;
    }

    public File uploadFile(String purpose, String filepath) {
        java.io.File file = new java.io.File(filepath);
        RequestBody purposeBody = RequestBody.create(okhttp3.MultipartBody.FORM, purpose);
        RequestBody fileBody = RequestBody.create(MediaType.parse("text"), file);
        MultipartBody.Part body = MultipartBody.Part.createFormData("file", filepath, fileBody);

        return execute(api.uploadFile(purposeBody, body));
    }

    public DeleteResult deleteFile(String fileId) {
        return execute(api.deleteFile(fileId));
    }

    public File retrieveFile(String fileId) {
        return execute(api.retrieveFile(fileId));
    }

    public FineTuneResult createFineTune(FineTuneRequest request) {
        return execute(api.createFineTune(request));
    }

    public CompletionResult createFineTuneCompletion(CompletionRequest request) {
        return execute(api.createFineTuneCompletion(request));
    }

    public List<FineTuneResult> listFineTunes() {
        return execute(api.listFineTunes()).data;
    }

    public FineTuneResult retrieveFineTune(String fineTuneId) {
        return execute(api.retrieveFineTune(fineTuneId));
    }

    public FineTuneResult cancelFineTune(String fineTuneId) {
        return execute(api.cancelFineTune(fineTuneId));
    }

    public List<FineTuneEvent> listFineTuneEvents(String fineTuneId) {
        return execute(api.listFineTuneEvents(fineTuneId)).data;
    }

    public DeleteResult deleteFineTune(String fineTuneId) {
        return execute(api.deleteFineTune(fineTuneId));
    }

    public ImageResult createImage(CreateImageRequest request) {
        return execute(api.createImage(request));
    }

    public ImageResult createImageEdit(CreateImageEditRequest request, String imagePath,
                                       String maskPath) {
        java.io.File image = new java.io.File(imagePath);
        java.io.File mask = null;
        if (maskPath != null) {
            mask = new java.io.File(maskPath);
        }
        return createImageEdit(request, image, mask);
    }

    public ImageResult createImageEdit(CreateImageEditRequest request, java.io.File image,
                                       java.io.File mask) {
        RequestBody imageBody = RequestBody.create(MediaType.parse("image"), image);

        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MediaType.get("multipart/form-data"))
                .addFormDataPart("prompt", request.getPrompt())
                .addFormDataPart("size", request.getSize())
                .addFormDataPart("model", request.getModel())
                .addFormDataPart("response_format", request.getResponseFormat())
                .addFormDataPart("image", "image", imageBody);

        if (request.getN() != null) {
            builder.addFormDataPart("n", request.getN().toString());
        }

        if (mask != null) {
            RequestBody maskBody = RequestBody.create(MediaType.parse("image"), mask);
            builder.addFormDataPart("mask", "mask", maskBody);
        }

        return execute(api.createImageEdit(builder.build()));
    }

    public ImageResult createImageVariation(CreateImageVariationRequest request, String imagePath) {
        java.io.File image = new java.io.File(imagePath);
        return createImageVariation(request, image);
    }

    public ImageResult createImageVariation(CreateImageVariationRequest request,
                                            java.io.File image) {
        RequestBody imageBody = RequestBody.create(MediaType.parse("image"), image);

        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MediaType.get("multipart/form-data"))
                .addFormDataPart("size", request.getSize())
                .addFormDataPart("model", request.getModel())
                .addFormDataPart("response_format", request.getResponseFormat())
                .addFormDataPart("image", "image", imageBody);

        if (request.getN() != null) {
            builder.addFormDataPart("n", request.getN().toString());
        }

        return execute(api.createImageVariation(builder.build()));
    }

    public ModerationResult createModeration(ModerationRequest request) {
        return execute(api.createModeration(request));
    }

    /**
     * Calls the Open AI api, returns the response, and parses error messages if the request fails
     */
    public static <T> T execute(Single<T> apiCall) {
        try {
            return apiCall.blockingGet();
        } catch (HttpException e) {
            try {
                if (e.response() == null || e.response().errorBody() == null) {
                    throw e;
                }
                String errorBody = e.response().errorBody().string();

                OpenAiError error = mapper.readValue(errorBody, OpenAiError.class);
                throw new OpenAiHttpException(error, e, e.code());
            } catch (IOException ex) {
                // couldn't parse OpenAI error
                throw e;
            }
        }
    }

    public void loading(ResultCallBack resultCallBack) {
        Observable.interval(0, 5, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<Long>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        compositeDisposable.add(d);
                    }

                    @Override
                    public void onNext(@NonNull Long aLong) {
                        resultCallBack.onLoading(true);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {

                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    public void loadingG(ResultGCallBack resultCallBack) {
        Observable.interval(0, 5, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<Long>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        compositeDisposable.add(d);
                    }

                    @Override
                    public void onNext(@NonNull Long aLong) {
                        resultCallBack.onLoading(true);
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {

                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    private Disposable streamLoadingDisposable;

    public void loading(StreamCallBack streamCallBack) {

        Observable.interval(0, 5, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<Long>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        streamLoadingDisposable = d;
                        compositeDisposable.add(d);
                    }

                    @Override
                    public void onNext(@NonNull Long aLong) {
                        if (isStreamFirstLoading) {
                            if (streamCallBack != null) streamCallBack.onLoading(true);
                        } else {
                            if (streamLoadingDisposable != null) {
                                compositeDisposable.remove(streamLoadingDisposable);
                            }
                        }
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        if (streamLoadingDisposable != null) {
                            compositeDisposable.remove(streamLoadingDisposable);
                        }
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    public void loadingG(StreamGCallBack streamCallBack) {

        Observable.interval(0, 5, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<Long>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        streamLoadingDisposable = d;
                        compositeDisposable.add(d);
                    }

                    @Override
                    public void onNext(@NonNull Long aLong) {
                        if (isStreamFirstLoading) {
                            if (streamGCallBack != null) streamGCallBack.onLoading(true);
                        } else {
                            if (streamLoadingDisposable != null) {
                                compositeDisposable.remove(streamLoadingDisposable);
                            }
                        }
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        if (streamLoadingDisposable != null) {
                            compositeDisposable.remove(streamLoadingDisposable);
                        }
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    public <T> void baseCompletion(int type, CompletionCallBack<T> callBack) {
        sortCompletion(type, null, callBack);
    }

    public <T> void sortCompletion(int type, CompletionRequest completionRequest,
                                   CompletionCallBack<T> callBack) {

        switch (type) {
            case listModels: {
                createCompletion(api.listModels(), callBack);
            }
        }

    }


    public <T> void createCompletion(Single<T> apiCall, CompletionCallBack<?> callBack) {

        compositeDisposable.clear();

        apiCall
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((new SingleObserver<T>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable disposable) {

                    }

                    @Override
                    public void onSuccess(@NonNull T t) {
                        callBack.onSuccess(t);
                        compositeDisposable.clear();
                    }

                    @Override
                    public void onError(@NonNull Throwable throwable) {

                        HttpException e;
                        if (throwable instanceof HttpException) {
                            e = (HttpException) throwable;
                        } else {
                            callBack.onError(null, throwable);
                            return;
                        }

                        try {
                            if (e.response() == null || e.response().errorBody() == null) {
                                callBack.onError(null, throwable);
                            } else {
                                String errorBody = e.response().errorBody().string();

                                OpenAiError error = mapper.readValue(errorBody, OpenAiError.class);
                                callBack.onError(new OpenAiHttpException(error, e, e.code()),
                                        throwable);
                            }
                        } catch (IOException ex) {
                            // couldn't parse OpenAI error
                            callBack.onError(null, throwable);
                        } finally {
                            compositeDisposable.clear();
                        }
                    }
                }));

    }

    public <T> void createChatCompletion(Single<T> apiCall, BaseMessage baseMessage,
                                         ResultCallBack resultCallBack) {

        compositeDisposable.clear();

        apiCall
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((new SingleObserver<T>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable disposable) {

                        compositeDisposable.add(disposable);
                        loading(resultCallBack);
                    }

                    @Override
                    public void onSuccess(@NonNull T t) {
                        resultCallBack.onSuccess((ChatCompletionResult) t);
                        resultCallBack.onLoading(false);
                        compositeDisposable.clear();
                    }

                    @Override
                    public void onError(@NonNull Throwable throwable) {

                        HttpException e;
                        if (throwable instanceof HttpException) {
                            e = (HttpException) throwable;
                        } else {
                            resultCallBack.onError(null, throwable);
                            resultCallBack.onLoading(false);
                            compositeDisposable.clear();
                            return;
                        }

                        try {
                            if (e.response() == null || e.response().errorBody() == null) {
                                resultCallBack.onError(null, throwable);
                            } else {
                                String errorBody = e.response().errorBody().string();

                                OpenAiError error = mapper.readValue(errorBody, OpenAiError.class);
                                resultCallBack.onError(new OpenAiHttpException(error, e, e.code()),
                                        throwable);
                            }
                        } catch (IOException ex) {
                            // couldn't parse OpenAI error
                            resultCallBack.onError(null, throwable);
                        } finally {
                            resultCallBack.onLoading(false);
                            compositeDisposable.clear();
                        }

                    }
                }));

    }

    public <T> void createChatGCompletion(Single<T> apiCall, BaseMessage baseMessage,
                                          ResultGCallBack resultCallBack) {

        compositeDisposable.clear();

        apiCall
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((new SingleObserver<T>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable disposable) {

                        compositeDisposable.add(disposable);
                        loadingG(resultCallBack);
                    }

                    @Override
                    public void onSuccess(@NonNull T t) {
                        resultCallBack.onSuccess((ChatGCompletionResponse) t);
                        resultCallBack.onLoading(false);
                        compositeDisposable.clear();
                    }

                    @Override
                    public void onError(@NonNull Throwable throwable) {


                        HttpException e;
                        if (throwable instanceof HttpException) {
                            e = (HttpException) throwable;
                        } else {
                            resultCallBack.onError(null, throwable);
                            resultCallBack.onLoading(false);
                            compositeDisposable.clear();
                            return;
                        }

                        try {
                            if (e.response() == null || e.response().errorBody() == null) {
                                resultCallBack.onError(null, throwable);
                            } else {
                                String errorBody = e.response().errorBody().string();

                                GoogleError error = mapper.readValue(errorBody, GoogleError.class);
                                resultCallBack.onError(new GoogleHttpException(error,throwable),
                                        throwable);
                            }
                        } catch (IOException ex) {
                            // couldn't parse OpenAI error
                            resultCallBack.onError(null, throwable);
                        } finally {
                            resultCallBack.onLoading(false);
                            compositeDisposable.clear();
                        }

                    }
                }));

    }

    private volatile boolean isStreamFirstLoading;
    private StreamCallBack streamCallBack;

    public void streamChatCompletion(ChatCompletionRequest request, StreamCallBack callBack) {

        request.setStream(true);
        isStreamFirstLoading = true;
        loading(callBack);
        //因为on在取消stream请求的问题（具体看ResponseBodyCallback），无法执行onCompletion，所以需要折中调用onCompletion
        streamCallBack = callBack;

        Call<ResponseBody> apiCall = api.createChatCompletionStream(request);
        Disposable disposable = stream(apiCall, ChatCompletionChunk.class)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .forEachWhile(chatCompletionChunk -> {

                            if (isStreamFirstLoading) {
                                isStreamFirstLoading = false;
                                if (streamCallBack != null) streamCallBack.onLoading(false);
                            }

                            if (streamCallBack != null) streamCallBack.onSuccess(chatCompletionChunk);

                            //返回true代表继续回调，false代表执行完成回调
                            return chatCompletionChunk.getChoices() == null
                                    || chatCompletionChunk.getChoices().size() <= 0
                                    || chatCompletionChunk.getChoices().get(0).getFinishReason() == null;
                        },
                        throwable -> {

                            HttpException e;
                            if (throwable instanceof HttpException) {
                                e = (HttpException) throwable;
                            } else {
                                if (streamCallBack != null) streamCallBack.onError(null, throwable);

                                if (streamCallBack != null) streamCallBack.onLoading(false);
                                streamCallBack = null;
                                compositeDisposable.clear();

                                return;
                            }

                            try {
                                if (e.response() == null || e.response().errorBody() == null) {
                                    if (streamCallBack != null)
                                        streamCallBack.onError(null, throwable);
                                } else {
                                    String errorBody = e.response().errorBody().string();

                                    OpenAiError error = mapper.readValue(errorBody,
                                            OpenAiError.class);
                                    if (streamCallBack != null)
                                        streamCallBack.onError(new OpenAiHttpException(error, e,
                                                        e.code()),
                                                throwable);
                                }
                            } catch (IOException ex) {
                                // couldn't parse OpenAI error
                                if (streamCallBack != null) streamCallBack.onError(null, throwable);
                            } finally {
                                if (streamCallBack != null) streamCallBack.onLoading(false);
                                streamCallBack = null;
                                compositeDisposable.clear();
                            }
                        },
                        () -> {
                            if (streamCallBack != null) streamCallBack.onCompletion();
                            streamCallBack = null;
                            compositeDisposable.clear();
                        });

        compositeDisposable.add(disposable);
        requestList.put("streamChatCompletion", apiCall);
    }


    private StreamGCallBack streamGCallBack;
    public void streamChatGCompletion(ChatGCompletionRequest request, String model,  StreamGCallBack callBack) {

        isStreamFirstLoading = true;
        loadingG(callBack);
        //因为on在取消stream请求的问题（具体看ResponseBodyCallback），无法执行onCompletion，所以需要折中调用onCompletion
        streamGCallBack = callBack;

        Call<ResponseBody> apiCall = api.createGChatCompletionStream(model, request);
        Disposable disposable = streamG(apiCall, ChatGCompletionResponse.class)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .forEachWhile(ChatGCompletionResponse -> {

                            if (isStreamFirstLoading) {
                                isStreamFirstLoading = false;
                                if (streamGCallBack != null) streamGCallBack.onLoading(false);
                            }

                            if (streamGCallBack != null) streamGCallBack.onSuccess(ChatGCompletionResponse);

                            //返回true代表继续回调，false代表执行完成回调
                            return ChatGCompletionResponse.getCandidates() == null
                                    || ChatGCompletionResponse.getCandidates().size() <= 0
                                    || ChatGCompletionResponse.getCandidates().get(0).getContent() == null
                                    || ChatGCompletionResponse.getCandidates().get(0).getContent().getParts() == null
                                    || ChatGCompletionResponse.getCandidates().get(0).getContent().getParts().size() <= 0
                                    || ChatGCompletionResponse.getCandidates().get(0).getContent().getParts().get(0) == null
                                    || !ChatGCompletionResponse.getCandidates().get(0).getContent().getParts().get(0).getText().equals("")
                                    ;
                        },
                        throwable -> {

                            HttpException e;
                            if (throwable instanceof HttpException) {
                                e = (HttpException) throwable;
                            } else {
                                if (streamGCallBack != null) streamGCallBack.onError(null, throwable);
                                return;
                            }

                            try {
                                if (e.response() == null || e.response().errorBody() == null) {
                                    if (streamGCallBack != null)
                                        streamGCallBack.onError(null, throwable);
                                } else {
                                    String errorBody = e.response().errorBody().string();

                                    GoogleError error = mapper.readValue(errorBody,
                                            GoogleError.class);
                                    if (streamGCallBack != null)
                                        streamGCallBack.onError(new GoogleHttpException(error, e),
                                                throwable);
                                }
                            } catch (IOException ex) {
                                // couldn't parse OpenAI error
                                if (streamGCallBack != null) streamGCallBack.onError(null, throwable);
                            } finally {
                                if (streamGCallBack != null) streamGCallBack.onLoading(false);
                                streamGCallBack = null;
                                compositeDisposable.clear();
                            }
                        },
                        () -> {
                            if (streamGCallBack != null) streamGCallBack.onCompletion();
                            streamGCallBack = null;
                            compositeDisposable.clear();
                        });

        compositeDisposable.add(disposable);
        requestList.put("streamChatCompletion", apiCall);
    }

    public interface StreamCallBack {

        void onSuccess(ChatCompletionChunk result);

        void onError(OpenAiHttpException error, Throwable Throwable);

        void onCompletion();

        void onLoading(boolean isLoading);

    }
    public interface StreamGCallBack {

        void onSuccess(ChatGCompletionResponse result);

        void onError(GoogleHttpException error, Throwable Throwable);

        void onCompletion();

        void onLoading(boolean isLoading);

    }


    public interface CompletionCallBack<T> {
        void onSuccess(Object o);

        void onError(OpenAiHttpException error, Throwable Throwable);
    }

    public interface ResultCallBack {

        void onSuccess(ChatCompletionResult result);

        void onError(OpenAiHttpException error, Throwable Throwable);

        void onLoading(boolean isLoading);

    }

    public interface ResultGCallBack {

        void onSuccess(ChatGCompletionResponse result);

        void onError(GoogleHttpException error, Throwable Throwable);

        void onLoading(boolean isLoading);

    }


    /**
     * Calls the Open AI api and returns a Flowable of SSE for streaming
     * omitting the last message.
     *
     * @param apiCall The api call
     */
    public static Flowable<SSE> stream(Call<ResponseBody> apiCall) {
        return stream(apiCall, false);
    }

    public static Flowable<SSE> streamG(Call<ResponseBody> apiCall) {
        return streamG(apiCall, false);
    }

    /**
     * Calls the Open AI api and returns a Flowable of SSE for streaming.
     *
     * @param apiCall  The api call
     * @param emitDone If true the last message ([DONE]) is emitted
     */
    public static Flowable<SSE> stream(Call<ResponseBody> apiCall, boolean emitDone) {
        //apiCall.enqueue为retrofit手动调用okHttp，所以默认情况下，在Android平台，因为平台判断
        //所以默认回调在主线程，但是这里ResponseBodyCallback内部进行数据读写、网络等耗时操作，需要在子线程进行操作
        //方法有两种，可以在retrofit传入线程池，或者ResponseBodyCallback回调中切换到子线程
        return Flowable.create(emitter -> apiCall.enqueue(new ResponseBodyCallback(emitter,
                emitDone)), BackpressureStrategy.BUFFER);
    }

    public static Flowable<SSE> streamG(Call<ResponseBody> apiCall, boolean emitDone) {
        //apiCall.enqueue为retrofit手动调用okHttp，所以默认情况下，在Android平台，因为平台判断
        //所以默认回调在主线程，但是这里ResponseBodyCallback内部进行数据读写、网络等耗时操作，需要在子线程进行操作
        //方法有两种，可以在retrofit传入线程池，或者ResponseBodyCallback回调中切换到子线程
        return Flowable.create(emitter -> apiCall.enqueue(new ResponseBodyGCallback(emitter,
                emitDone)), BackpressureStrategy.BUFFER);
    }

    /**
     * Calls the Open AI api and returns a Flowable of type T for streaming
     * omitting the last message.
     *
     * @param apiCall The api call
     * @param cl      Class of type T to return
     */
    public static <T> Flowable<T> stream(Call<ResponseBody> apiCall, Class<T> cl) {

        return stream(apiCall).map(sse -> mapper.readValue(sse.getData(), cl));
    }

    public static <T> Flowable<T> streamG(Call<ResponseBody> apiCall, Class<T> cl) {

        return streamG(apiCall).map(sse -> mapper.readValue(sse.getData(), cl));
    }

    public void clearRequest() {

        //todo rxJava的通用网络请求取消未做
        for (Map.Entry<String, Call<ResponseBody>> entry : requestList.entrySet()) {
            Call<ResponseBody> call = entry.getValue();

            if (call != null && call.isExecuted() && !call.isCanceled()) {
                call.cancel();
            }
        }
        requestList.clear();
    }

    public void clean() {
        clean(true);
    }

    public void clean(boolean isExecutor) {
        clearRequest();
        if (streamCallBack != null){
            streamCallBack.onLoading(false);
        }
        if (streamCallBack != null){
            streamCallBack.onCompletion();
        }
        if (streamGCallBack != null){
            streamGCallBack.onLoading(false);
        }
        if (streamGCallBack != null){
            streamGCallBack.onCompletion();
        }
        compositeDisposable.clear();
        if (isExecutor) shutdownExecutor();
    }

    /**
     * Shuts down the OkHttp ExecutorService.
     * The default behaviour of OkHttp's ExecutorService (ConnectionPool)
     * is to shut down after an idle timeout of 60s.
     * Call this method to shut down the ExecutorService immediately.
     */
    public void shutdownExecutor() {
        Objects.requireNonNull(this.executorService, "executorService must be set in order to " +
                "shut down");
        this.executorService.shutdown();
    }

    public static String formatUrl(String url) {

        HttpUrl newBaseUrl = HttpUrl.parse(url);

        if (newBaseUrl == null) return "";

        String formatUrl = newBaseUrl.toString();
        if (!"".equals(newBaseUrl.pathSegments().get(newBaseUrl.pathSegments().size() - 1))) {
            formatUrl = formatUrl +"/";
        }

        return formatUrl;
    }

    public static OpenAiApi buildApi(String token, long second) {
        ObjectMapper mapper = defaultObjectMapper();
        OkHttpClient client = defaultClient(token, second, BASE_URL, false);
        Retrofit retrofit = defaultRetrofit(client, mapper, BASE_URL,
                client.dispatcher().executorService());

        return retrofit.create(OpenAiApi.class);
    }

    public static ObjectMapper defaultObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);
        return mapper;
    }

    public static OkHttpClient defaultClient(String token, long second, String url, boolean isGoogle) {

        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor(s -> {
//            Log.i("test",s);
        });
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);

        return new OkHttpClient.Builder()
                .addInterceptor(new AuthenticationInterceptor(token, url, isGoogle))
//                .addInterceptor(loggingInterceptor)
                .connectionPool(new ConnectionPool(5, 1, TimeUnit.SECONDS))
                .readTimeout(second * 1000, TimeUnit.MILLISECONDS)
                .build();
    }

    public static Retrofit defaultRetrofit(OkHttpClient client, ObjectMapper mapper, String url,
                                           Executor executor) {
        return new Retrofit.Builder()
                .baseUrl(url)
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create(mapper))
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .callbackExecutor(executor)
                .build();
    }
}