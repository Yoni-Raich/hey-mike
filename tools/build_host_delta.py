"""Compile the same JNI adapter for Windows JVM integration tests (requires VS Build Tools)."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
from tools.prepare_file_delta import ROOT, sources

def main():
    parser=argparse.ArgumentParser(); parser.add_argument("--output",type=Path,required=True); parser.add_argument("--sources",type=Path,required=True)
    args=parser.parse_args(); args.output=args.output.resolve(); args.sources=args.sources.resolve(); args.output.mkdir(parents=True,exist_ok=True)
    vswhere=Path(os.environ.get("ProgramFiles(x86)",r"C:\Program Files (x86)"))/"Microsoft Visual Studio/Installer/vswhere.exe"
    install=subprocess.check_output([str(vswhere),"-latest","-products","*","-property","installationPath"],text=True).strip()
    vcvars=Path(install)/"VC/Auxiliary/Build/vcvars64.bat"
    shell_command=f'call "{vcvars}" >nul && set'
    environment=subprocess.check_output("cmd.exe /d /c " + shell_command,text=True)
    env=os.environ.copy()
    # Do not print environment: it may include credentials unrelated to this task.
    for line in environment.splitlines():
        if "=" in line and not line.startswith("="):
            key,value=line.split("=",1); env[key]=value
    compiler=Path(env["VCToolsInstallDir"])/"bin/Hostx64/x64/cl.exe"
    java=Path(env.get("JAVA_HOME",r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"))
    deps=sources(args.sources)
    hdiff,zstd,xxhash=(deps[x] for x in ("hdiff","zstd","xxhash"))
    files=[ROOT/"workspace/src/main/cpp/delta_jni.c",hdiff/"libHDiffPatch/HPatch/patch.c"]
    files += [zstd/"lib"/p for p in ("common/debug.c","common/entropy_common.c","common/error_private.c","common/fse_decompress.c","common/xxhash.c","common/zstd_common.c","decompress/huf_decompress.c","decompress/zstd_ddict.c","decompress/zstd_decompress.c","decompress/zstd_decompress_block.c")]
    flags=["/nologo","/c","/O2","/MD","/std:c11","/D_CRT_SECURE_NO_WARNINGS","/D_IS_USED_MULTITHREAD=0","/DNDEBUG","/DZSTD_DISABLE_ASM=1","/DZSTD_LEGACY_SUPPORT=0"]
    flags += ["/I"+str(p) for p in (java/"include",java/"include/win32",hdiff,zstd/"lib",xxhash)]
    objects=[]
    for index,file in enumerate(files):
        obj=args.output/(str(index)+".obj")
        subprocess.run([str(compiler),*flags,str(file),"/Fo"+str(obj)],env=env,cwd=args.output,check=True)
        objects.append(str(obj))
    output=args.output/"mike_delta.dll"
    subprocess.run([str(compiler),"/nologo","/LD","/MD",*objects,"/Fe"+str(output),"/link","/IMPLIB:"+str(args.output/"mike_delta.lib")],env=env,cwd=args.output,check=True)
    print("Host JNI:",output,"bytes:",output.stat().st_size)

if __name__=="__main__": main()
