package io.github.kunaigear.dailycams.obscura.model.resource;

import com.google.gson.annotations.SerializedName;
import io.github.kunaigear.dailycams.obscura.model.resource.state.MultipartDefinition;
import io.github.kunaigear.dailycams.obscura.model.resource.state.Variant;

import java.util.List;
import java.util.Map;

public class RPBlockState {

    @SerializedName("variants")
    public Map<String, Variant> variants;

    @SerializedName("multipart")
    public List<MultipartDefinition> multipart;
}
