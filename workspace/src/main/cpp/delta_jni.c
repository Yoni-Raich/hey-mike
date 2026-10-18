/* Streaming HDIFFW26 adapter. HDiffPatch/zstd/xxHash are staged from pinned sources. */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
#ifdef _WIN32
#include <io.h>
#include <windows.h>
#define seek64 _fseeki64
#define tell64 _ftelli64
#else
#include <unistd.h>
#include <fcntl.h>
#include <sys/syscall.h>
#define seek64 fseeko
#define tell64 ftello
#endif
#include "libHDiffPatch/HPatch/patch.h"
#define _ChecksumPlugin_xxh128
#define XXH_INLINE_ALL
#include "checksum_plugin_demo.h"
#include "zstd.h"

typedef struct Control {
    JNIEnv* env; jobject object; jmethodID cancel; jmethodID progress;
    uint64_t total; uint64_t written;
} Control;
#ifdef _WIN32
static __declspec(thread) Control* active_control;
#else
static __thread Control* active_control;
#endif

/* UTF-8 byte arrays avoid JNI's modified UTF-8 encoding for emoji filenames. */
static char* publication_path(JNIEnv* env, jbyteArray input) {
    if (!input) return NULL;
    jsize size=(*env)->GetArrayLength(env,input);
    if (size<=0 || size>262144) return NULL;
    char* value=(char*)malloc((size_t)size+1);
    if (!value) return NULL;
    (*env)->GetByteArrayRegion(env,input,0,size,(jbyte*)value);
    if ((*env)->ExceptionCheck(env) || memchr(value,0,(size_t)size)) { free(value); return NULL; }
    value[size]=0;
    return value;
}
#ifdef _WIN32
static wchar_t* publication_wide(const char* input) {
    int size=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,input,-1,NULL,0);
    if (!size) return NULL;
    wchar_t* value=(wchar_t*)malloc((size_t)size*sizeof(wchar_t));
    if (value && !MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,input,-1,value,size)) { free(value); return NULL; }
    return value;
}
#endif
JNIEXPORT jint JNICALL Java_dev_androidagent_workspace_NativeAtomicPublisher_renameNoReplace
  (JNIEnv* env,jobject self,jbyteArray old_path,jbyteArray new_path) {
    (void)self;
    char* old=publication_path(env,old_path); char* target=publication_path(env,new_path);
    int result=EINVAL;
    if (!old || !target) goto publication_done;
#ifdef _WIN32
    wchar_t* old_wide=publication_wide(old); wchar_t* target_wide=publication_wide(target);
    if (!old_wide || !target_wide) result=EINVAL;
    else if (MoveFileExW(old_wide,target_wide,0)) result=0;
    else {
        DWORD error=GetLastError();
        result=(error==ERROR_ALREADY_EXISTS || error==ERROR_FILE_EXISTS)?EEXIST:
            (error==ERROR_FILE_NOT_FOUND || error==ERROR_PATH_NOT_FOUND)?ENOENT:EIO;
    }
    free(old_wide); free(target_wide);
#elif defined(__ANDROID__) && defined(__NR_renameat2)
    /* No check-then-rename race, hard link, copy or fallback that could overwrite a target. */
    result=syscall(__NR_renameat2,AT_FDCWD,old,AT_FDCWD,target,1 /* RENAME_NOREPLACE */)==0?0:errno;
#else
    result=ENOSYS;
#endif
publication_done:
    free(old); free(target); return result;
}
typedef struct FileIO { FILE* file; Control* control; } FileIO;

static int cancelled(Control* c) {
    if ((*c->env)->ExceptionCheck(c->env)) return 1;
    return (*c->env)->CallBooleanMethod(c->env,c->object,c->cancel)==JNI_TRUE;
}
static hpatch_BOOL read_data(const hpatch_TStreamInput* stream,hpatch_StreamPos_t pos,unsigned char* data,unsigned char* end) {
    FileIO* io=(FileIO*)stream->streamImport;
    if (cancelled(io->control) || pos>stream->streamSize || (uint64_t)(end-data)>stream->streamSize-pos || seek64(io->file,(int64_t)pos,SEEK_SET)) return hpatch_FALSE;
    while (data<end) {
        size_t n=(size_t)(end-data); if(n>65536)n=65536;
        if(cancelled(io->control) || fread(data,1,n,io->file)!=n) return hpatch_FALSE;
        data+=n;
    }
    return hpatch_TRUE;
}
static hpatch_BOOL write_data(const hpatch_TStreamOutput* stream,hpatch_StreamPos_t pos,const unsigned char* data,const unsigned char* end) {
    FileIO* io=(FileIO*)stream->streamImport; Control* c=io->control;
    if(cancelled(c) || pos>c->total || pos!=c->written || (uint64_t)(end-data)>c->total-pos) return hpatch_FALSE;
    while(data<end) {
        size_t n=(size_t)(end-data); if(n>65536)n=65536;
        if(cancelled(c) || fwrite(data,1,n,io->file)!=n) return hpatch_FALSE;
        data+=n; c->written+=n;
        (*c->env)->CallVoidMethod(c->env,c->object,c->progress,(jlong)c->written,(jlong)c->total);
    }
    return cancelled(c)?hpatch_FALSE:hpatch_TRUE;
}

/* The upstream demo permits a 1GiB dictionary. This decoder enforces an 8MiB maximum. */
typedef struct Decoder {
    Control* control;
    const hpatch_TStreamInput* stream; hpatch_StreamPos_t pos,end;
    ZSTD_DStream* zstd; ZSTD_inBuffer input; unsigned char buffer[65536];
} Decoder;
static hpatch_BOOL can_open(const char* type) { return strcmp(type,"zstd")==0; }
static hpatch_decompressHandle open_decoder(hpatch_TDecompress* plugin,hpatch_StreamPos_t size,const hpatch_TStreamInput* stream,hpatch_StreamPos_t begin,hpatch_StreamPos_t end) {
    Decoder* d=(Decoder*)calloc(1,sizeof(Decoder)); (void)plugin; (void)size;
    if(!d)return 0;
    d->control=active_control;
    d->stream=stream; d->pos=begin; d->end=end; d->input.src=d->buffer;
    d->zstd=ZSTD_createDStream();
    if(!d->zstd || ZSTD_isError(ZSTD_initDStream(d->zstd)) || ZSTD_isError(ZSTD_DCtx_setParameter(d->zstd,ZSTD_d_windowLogMax,23))) {
        ZSTD_freeDStream(d->zstd); free(d); return 0;
    }
    return d;
}
static hpatch_BOOL close_decoder(hpatch_TDecompress* plugin,hpatch_decompressHandle handle) {
    Decoder* d=(Decoder*)handle; (void)plugin; ZSTD_freeDStream(d->zstd); free(d); return hpatch_TRUE;
}
static hpatch_BOOL decode_part(hpatch_decompressHandle handle,unsigned char* data,unsigned char* end) {
    Decoder* d=(Decoder*)handle;
    ZSTD_outBuffer output={data,(size_t)(end-data),0};
    while(output.pos<output.size) {
        size_t previous=output.pos; size_t in_previous=d->input.pos;
        if(d->input.pos==d->input.size && d->pos<d->end) {
            size_t n=(size_t)((d->end-d->pos)>sizeof(d->buffer)?sizeof(d->buffer):(d->end-d->pos));
            if(!d->stream->read(d->stream,d->pos,d->buffer,d->buffer+n))return hpatch_FALSE;
            d->pos+=n; d->input.size=n; d->input.pos=0; in_previous=0;
        }
        /* Every decode step checks Stop even when input is buffered. */
        if(cancelled(d->control))return hpatch_FALSE;
        if(ZSTD_isError(ZSTD_decompressStream(d->zstd,&output,&d->input)))return hpatch_FALSE;
        if(previous==output.pos && in_previous==d->input.pos)return hpatch_FALSE;
    }
    return hpatch_TRUE;
}
static hpatch_TDecompress decompressor={can_open,open_decoder,close_decoder,decode_part};

static hpatch_BOOL info(struct winpatch_listener_t* listener,const hpatch_windowDiffInfo* header,hpatch_TDecompress** decoder,struct hpatch_TChecksum** checksum,hpatch_BOOL* check_new,hpatch_BOOL* check_old,hpatch_BOOL* check_diff,unsigned char** memory,unsigned char** end) {
    Control* c=(Control*)listener->import;
    if(cancelled(c) || header->maxWindowOldSize>2*1024*1024 || header->maxStepMemSize>256*1024 || header->windowMetaCount>65536 || header->checksumByteSize!=16 || strcmp(header->checksumType,"xxh128") || strcmp(header->compressType,"zstd"))return hpatch_FALSE;
    *decoder=&decompressor; *checksum=&xxh128ChecksumPlugin;
    *check_new=hpatch_TRUE; *check_old=hpatch_TRUE; *check_diff=hpatch_TRUE;
    size_t bytes=(size_t)(header->maxWindowOldSize+header->maxStepMemSize+8*1024*1024);
    *memory=(unsigned char*)malloc(bytes); if(!*memory)return hpatch_FALSE;
    *end=*memory+bytes; return hpatch_TRUE;
}
static void finished(struct winpatch_listener_t* listener,unsigned char* memory,unsigned char* end) { (void)listener; (void)end; free(memory); }

JNIEXPORT jint JNICALL Java_dev_androidagent_workspace_NativeDeltaApplier_patch(JNIEnv* env,jobject self,jstring old_path,jstring diff_path,jstring out_path,jlong old_size,jlong new_size,jobject object) {
    (void)self; int result=100; const char *old=0,*diff=0,*output=0;
    FILE *old_file=0,*diff_file=0,*out_file=0;
    Control c={0}; c.env=env; c.object=object; c.total=(uint64_t)new_size;
    active_control=&c;
    jclass type=(*env)->GetObjectClass(env,object);
    c.cancel=(*env)->GetMethodID(env,type,"cancelled","()Z");
    c.progress=(*env)->GetMethodID(env,type,"progress","(JJ)V");
    if(!c.cancel || !c.progress || old_size<0 || new_size<0 || cancelled(&c))goto done;
    old=(*env)->GetStringUTFChars(env,old_path,0); diff=(*env)->GetStringUTFChars(env,diff_path,0); output=(*env)->GetStringUTFChars(env,out_path,0);
    if(!old || !diff || !output)goto done;
    old_file=fopen(old,"rb"); diff_file=fopen(diff,"rb");
    if(!old_file || !diff_file)goto done;
    if(seek64(old_file,0,SEEK_END) || tell64(old_file)!=old_size || seek64(diff_file,0,SEEK_END))goto done;
    int64_t diff_size=tell64(diff_file); if(diff_size<0)goto done;
    FileIO a={old_file,&c},b={diff_file,&c},o={0,&c};
    hpatch_TStreamInput old_stream={&a,(hpatch_StreamPos_t)old_size,read_data,0};
    hpatch_TStreamInput diff_stream={&b,(hpatch_StreamPos_t)diff_size,read_data,0};
    hpatch_windowDiffInfo header;
    if(!getWindowDiffInfo(&header,&diff_stream,0) || header.oldDataSize!=(uint64_t)old_size || header.newDataSize!=(uint64_t)new_size)goto done;
    out_file=fopen(output,"wb"); if(!out_file)goto done; o.file=out_file;
    hpatch_TStreamOutput out_stream={&o,(hpatch_StreamPos_t)new_size,0,write_data};
    winpatch_listener_t listener={&c,info,finished};
    result=(int)patch_window_diff(&listener,&out_stream,&old_stream,&diff_stream,0,1);
    if(fflush(out_file)!=0 || c.written!=(uint64_t)new_size || cancelled(&c))result=101;
done:
    active_control=0;
    if(out_file)fclose(out_file); if(diff_file)fclose(diff_file); if(old_file)fclose(old_file);
    if(output)(*env)->ReleaseStringUTFChars(env,out_path,output);
    if(diff)(*env)->ReleaseStringUTFChars(env,diff_path,diff);
    if(old)(*env)->ReleaseStringUTFChars(env,old_path,old);
    return result;
}
