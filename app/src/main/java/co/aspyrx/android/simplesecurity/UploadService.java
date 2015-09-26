package co.aspyrx.android.simplesecurity;

import retrofit.client.Response;
import retrofit.http.Multipart;
import retrofit.http.PUT;
import retrofit.http.Part;
import retrofit.mime.TypedFile;

public interface UploadService {
    @Multipart
    @PUT("/upload/photo")
    Response uploadPhoto(@Part("photo") TypedFile photo);
}
