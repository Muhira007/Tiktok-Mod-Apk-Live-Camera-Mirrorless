#include <jni.h>
#include <android/bitmap.h>
#include <cstdint>

namespace {
inline uint8_t clamp8(int value) {
    return static_cast<uint8_t>(value < 0 ? 0 : (value > 255 ? 255 : value));
}

void throwException(JNIEnv* env, const char* type, const char* message) {
    jclass cls=env->FindClass(type);
    if(cls) env->ThrowNew(cls,message);
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_otglive_bridge_NativeImageProcessor_nativeBitmapToNv21(
        JNIEnv* env, jclass, jobject bitmap) {
    if(!bitmap) {
        throwException(env,"java/lang/NullPointerException","bitmap");
        return nullptr;
    }
    AndroidBitmapInfo info{};
    if(AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS
            || info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888) {
        throwException(env,"java/lang/IllegalArgumentException","RGBA_8888 bitmap required");
        return nullptr;
    }
    if((info.width&1u)||(info.height&1u)||info.width==0||info.height==0) {
        throwException(env,"java/lang/IllegalArgumentException","positive even dimensions required");
        return nullptr;
    }
    const uint64_t pixels=static_cast<uint64_t>(info.width)*info.height;
    if(pixels>(static_cast<uint64_t>(INT32_MAX)*2u/3u)) {
        throwException(env,"java/lang/IllegalArgumentException","bitmap too large");
        return nullptr;
    }
    jbyteArray output=env->NewByteArray(static_cast<jsize>(pixels*3u/2u));
    if(!output) return nullptr;
    void* source=nullptr;
    if(AndroidBitmap_lockPixels(env,bitmap,&source)!=ANDROID_BITMAP_RESULT_SUCCESS || !source) {
        env->DeleteLocalRef(output);
        throwException(env,"java/lang/IllegalStateException","cannot lock bitmap pixels");
        return nullptr;
    }
    auto* target=static_cast<uint8_t*>(env->GetPrimitiveArrayCritical(output,nullptr));
    if(!target) {
        AndroidBitmap_unlockPixels(env,bitmap);
        env->DeleteLocalRef(output);
        return nullptr;
    }
    uint8_t* yPlane=target;
    uint8_t* vuPlane=target+pixels;
    const auto* base=static_cast<const uint8_t*>(source);
    const uint32_t width=info.width;
    const uint32_t height=info.height;
    for(uint32_t row=0;row<height;row++) {
        const uint8_t* rgba=base+static_cast<size_t>(row)*info.stride;
        uint8_t* y=yPlane+static_cast<size_t>(row)*width;
        uint8_t* vu=vuPlane+static_cast<size_t>(row/2)*width;
        #pragma clang loop vectorize(enable)
        for(uint32_t col=0;col<width;col++) {
            const int r=rgba[col*4],g=rgba[col*4+1],b=rgba[col*4+2];
            y[col]=clamp8(((66*r+129*g+25*b+128)>>8)+16);
            if((row&1u)==0 && (col&1u)==0) {
                vu[col]=clamp8(((112*r-94*g-18*b+128)>>8)+128);
                vu[col+1]=clamp8(((-38*r-74*g+112*b+128)>>8)+128);
            }
        }
    }
    env->ReleasePrimitiveArrayCritical(output,target,0);
    AndroidBitmap_unlockPixels(env,bitmap);
    return output;
}
