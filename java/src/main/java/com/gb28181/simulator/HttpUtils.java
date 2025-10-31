package com.gb28181.simulator;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * HTTP请求工具类
 */
class HttpUtils {
    private static final Gson gson = new Gson();
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    /**
     * POST JSON请求
     */
    public static JsonObject postJson(String url, JsonObject jsonBody, String token) throws IOException {
        RequestBody body = RequestBody.create(
                jsonBody.toString(),
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("X-Access-Token", token)
                .addHeader("X_Access_Token", token)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected code " + response);
            }
            String responseBody = response.body().string();
            return gson.fromJson(responseBody, JsonObject.class);
        }
    }

    /**
     * GET请求（流式）
     */
    public static Response getStream(String url, String token) throws IOException {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(url).newBuilder();
        urlBuilder.addQueryParameter(":X_Access_Token", token);

        Request request = new Request.Builder()
                .url(urlBuilder.build())
                .get()
                .addHeader("X-Access-Token", token)
                .addHeader("X_Access_Token", token)
                .build();

        return client.newCall(request).execute();
    }

    /**
     * 分页查询设备列表
     */
    public static List<JsonObject> paginateDevices(String baseUrl, String token, int pageSize) throws IOException {
        List<JsonObject> devices = new ArrayList<>();
        int pageIndex = 0;

        while (true) {
            JsonObject body = new JsonObject();
            body.addProperty("pageIndex", pageIndex);
            body.addProperty("pageSize", pageSize);

            JsonArray sorts = new JsonArray();
            JsonObject sort = new JsonObject();
            sort.addProperty("name", "createTime");
            sort.addProperty("order", "desc");
            sorts.add(sort);
            body.add("sorts", sorts);
            body.add("terms", new JsonArray());

            JsonObject data = postJson(baseUrl + "/api/media/device/_query/", body, token);
            JsonArray arr = data.getAsJsonObject("result").getAsJsonArray("data");

            if (arr == null || arr.size() == 0) {
                break;
            }

            for (int i = 0; i < arr.size(); i++) {
                devices.add(arr.get(i).getAsJsonObject());
            }

            if (arr.size() < pageSize) {
                break;
            }
            pageIndex++;
        }

        return devices;
    }

    /**
     * 分页查询通道列表
     */
    public static List<JsonObject> paginateChannels(String baseUrl, String deviceId, String token, int pageSize) throws IOException {
        List<JsonObject> channels = new ArrayList<>();
        int pageIndex = 0;

        while (true) {
            JsonObject body = new JsonObject();
            body.addProperty("pageIndex", pageIndex);
            body.addProperty("pageSize", pageSize);

            JsonArray sorts = new JsonArray();
            JsonObject sort = new JsonObject();
            sort.addProperty("name", "modifyTime");
            sort.addProperty("order", "desc");
            sorts.add(sort);
            body.add("sorts", sorts);
            body.add("terms", new JsonArray());

            JsonObject data = postJson(
                    baseUrl + "/api/media/device/" + deviceId + "/channel/_query",
                    body,
                    token
            );
            JsonArray arr = data.getAsJsonObject("result").getAsJsonArray("data");

            if (arr == null || arr.size() == 0) {
                break;
            }

            for (int i = 0; i < arr.size(); i++) {
                channels.add(arr.get(i).getAsJsonObject());
            }

            if (arr.size() < pageSize) {
                break;
            }
            pageIndex++;
        }

        return channels;
    }
}

