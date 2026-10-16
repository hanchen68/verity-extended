package com.hanchen.veritycustomapi.cloud;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

public final class CloudBooleanAdapter extends TypeAdapter<Integer> {
    @Override
    public void write(JsonWriter out, Integer value) throws IOException {
        if (value == null) {
            out.nullValue();
        } else {
            out.value(value != 0 ? 1 : 0);
        }
    }

    @Override
    public Integer read(JsonReader in) throws IOException {
        JsonToken token = in.peek();
        if (token == JsonToken.NULL) {
            in.nextNull();
            return 0;
        }
        if (token == JsonToken.BOOLEAN) return in.nextBoolean() ? 1 : 0;
        if (token == JsonToken.NUMBER) return in.nextInt() == 0 ? 0 : 1;
        throw new IOException("云端布尔字段类型无效: " + token);
    }
}
