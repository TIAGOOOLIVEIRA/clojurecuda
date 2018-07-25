;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Dragan Djuric"}
    uncomplicate.clojurecuda.internal.impl
  (:require [uncomplicate.commons
             [core :refer [Releaseable release Info info wrap-float wrap-double wrap-long wrap-int]]
             [utils :as cu :refer [dragan-says-ex]]]
            [uncomplicate.clojurecuda.internal
             [protocols :refer :all]
             [constants :refer :all]
             [utils :refer [with-check error]]]
            [clojure.core.async :refer [go >!]])
  (:import [jcuda Pointer NativePointerObject JCudaAccessor]
           [jcuda.driver JCudaDriver CUdevice CUcontext CUdeviceptr CUmodule CUfunction
            CUstream CUresult CUstreamCallback CUevent JITOptions CUlinkState]
           [jcuda.nvrtc JNvrtc nvrtcProgram nvrtcResult]
           [java.nio ByteBuffer ByteOrder]
           java.nio.file.Path java.util.Arrays java.io.File))

(extend-type nil
  Wrapper
  (extract [_]
    nil)
  Wrappable
  (wrap [this]
    nil))

;; ==================== Release resources =======================

(defn native-pointer ^long [npo]
  (JCudaAccessor/getNativePointer npo))

(extend-type NativePointerObject
  Releaseable
  (release [this]
    (dragan-says-ex "It is not allowed to use and release raw JCuda objects. Use a safe wrapper."
                    {:this this})))

(extend-type CUdevice
  Releaseable
  (release [this]
    true)
  Wrappable
  (wrap [this]
    this)
  Wrapper
  (extract [this]
    this))

(extend-type CUfunction
  Releaseable
  (release [this]
    true)
  Wrappable
  (wrap [this]
    this)
  Wrapper
  (extract [this]
    this))

(defmacro ^:private deftype-wrapper [name release-method]
  (let [name-str (str name)]
    `(deftype ~name [ref#]
       Object
       (hashCode [this#]
         (hash (deref ref#)))
       (equals [this# other#]
         (= (deref ref#) (extract other#)))
       (toString [this#]
         (format "#%s[0x%s]" ~name-str (Long/toHexString (native-pointer (deref ref#)))))
       Wrapper
       (extract [this#]
         (deref ref#))
       Releaseable
       (release [this#]
         (locking ref#
           (when-let [d# (deref ref#)]
             (locking d#
               (with-check (~release-method d#) (vreset! ref# nil)))))
         true))))

(deftype-wrapper CUContext JCudaDriver/cuCtxDestroy)
(deftype-wrapper CUStream JCudaDriver/cuStreamDestroy)
(deftype-wrapper CUEvent JCudaDriver/cuEventDestroy)
(deftype-wrapper CUModule JCudaDriver/cuModuleUnload)
(deftype-wrapper CULinkState JCudaDriver/cuLinkDestroy)

(extend-type CUcontext
  Info
  (info [this]
    (info (wrap this)))
  Wrappable
  (wrap [ctx]
    (->CUContext (volatile! ctx))))

(extend-type CUstream
  Info
  (info [this]
    (info (wrap this)))
  Wrappable
  (wrap [stream]
    (->CUStream (volatile! stream))))

(extend-type CUmodule
  Info
  (info [this]
    (info (wrap this)))
  Wrappable
  (wrap [mod]
    (->CUModule (volatile! mod))))

(extend-type CUlinkState
  Info
  (info [this]
    (info (wrap this)))
  Wrappable
  (wrap [link-state]
    (->CULinkState (volatile! link-state))))

(extend-type CUevent
  Info
  (info [this]
    (info (wrap this)))
  Wrappable
  (wrap [event]
    (->CUEvent (volatile! event))))

;; ====================== Nvrtc program JIT ========================================

(defn ^:private nvrtc-error
  "Converts an CUDA Nvrtc error code to an ExceptionInfo with richer, user-friendly information."
  ([^long err-code details]
   (let [err (nvrtcResult/stringFor err-code)]
     (ex-info (format "NVRTC error: %s." err)
              {:name err :code err-code :type :nvrtc-error :details details})))
  ([err-code]
   (error err-code nil)))

(defmacro ^:private with-check-nvrtc
  "Evaluates `form` if `err-code` is not zero (`NVRTC_SUCCESS`), otherwise throws
  an appropriate `ExceptionInfo` with decoded informative details.
  It helps fith JCuda nvrtc methods that return error codes directly, while
  returning computation results through side-effects in arguments.
  "
  ([err-code form]
   `(cu/with-check nvrtc-error ~err-code ~form)))

(defn program*
  "Creates a CUDA program with `name`, from the `source-code`, and arrays of headers (as strings)
  and their names."
  [name source-code source-headers include-names]
  (let [res (nvrtcProgram.)]
    (with-check-nvrtc
      (JNvrtc/nvrtcCreateProgram res source-code name (count source-headers) source-headers include-names)
      res)))

(defn program-log*
  "Returns the log string generated by the previous compilation of `program`."
  [^nvrtcProgram program]
  (let [res (make-array String 1)]
    (with-check-nvrtc (JNvrtc/nvrtcGetProgramLog program res) (aget ^objects res 0))))

(defn compile*
  "Compiles the given `program` using an array of string `options`."
  ([^nvrtcProgram program options]
   (let [err (JNvrtc/nvrtcCompileProgram program (count options) options)]
     (if (= nvrtcResult/NVRTC_SUCCESS err)
       program
       (throw (nvrtc-error err (program-log* program)))))))

(defn ptx*
  "Returns the PTX generated by the previous compilation of `program`."
  ^String [^nvrtcProgram program]
  (let [res (make-array String 1)]
    (with-check-nvrtc (JNvrtc/nvrtcGetPTX program res) (aget ^objects res 0))))

(deftype NvrtcProgram [program]
  Object
  (hashCode [_]
    (hash @program))
  (equals [_ other]
    (= @program (extract other)))
  (toString [_]
    (format "#NvrtcProgram[0x%s]" (Long/toHexString (native-pointer @program))))
  Wrapper
  (extract [_]
    @program)
  Releaseable
  (release [this]
    (locking program
      (when-let [p @program]
        (locking p
          (with-check-nvrtc (JNvrtc/nvrtcDestroyProgram p) (vreset! program nil)))))
    true)
  ModuleLoad
  (module-load* [_ m]
    (with-check (JCudaDriver/cuModuleLoadData ^CUmodule m (ptx* @program)) m))
  (link-add* [_ link-state type options]
    (link-add* (ptx* @program) link-state type options)))

(extend-type nvrtcProgram
  Wrappable
  (wrap [prog]
    (->NvrtcProgram (volatile! prog))))

;; ==============================================================================

(extend-type Pointer
  WithOffset
  (with-offset [p byte-offset]
    (.withByteOffset p ^long byte-offset)))

;; =================== Context Management ==================================

(defn context*
  "Creates a CUDA context on the `device` using a raw integer `flag`.
  For available flags, see [[constants/ctx-flags]].
  "
  [dev ^long flags]
  (let [res (CUcontext.)]
    (with-check (JCudaDriver/cuCtxCreate res flags dev)
      {:dev (info dev) :flags flags}
      res)))

(defn current-context*
  "If `ctx` is provided, bounds it as current. Returns the CUDA context bound to the calling CPU thread.

  See [cuCtxGetCurrent](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__CTX.html)
  "
  ([]
   (let [ctx (CUcontext.)]
     (with-check (JCudaDriver/cuCtxGetCurrent ctx) ctx)))
  ([^CUcontext ctx]
   (with-check (JCudaDriver/cuCtxSetCurrent ctx) ctx)))

(defn pop-context*
  "Pops the current CUDA context from the current CPU thread.

  See [cuCtxPopCurrent](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__CTX.html)
  "
  []
  (let [ctx (CUcontext.)]
    (with-check (JCudaDriver/cuCtxPopCurrent ctx) true)))

(defn push-context*
  "Pushes a context on the current CPU thread.

  See [cuCtxPushCurrent](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__CTX.html)
  "
  [^CUcontext ctx]
  (with-check (JCudaDriver/cuCtxPushCurrent ctx) ctx))

;; ==================== Linear memory ================================================

(deftype CULinearMemory [cu p ^long s master]
  Releaseable
  (release [this]
    (if master
      (locking cu
        (when-let [c @cu]
          (locking c
            (with-check (JCudaDriver/cuMemFree c)
              (do
                (vreset! cu nil)
                (vreset! p nil)))))))
    true)
  Wrapper
  (extract [_]
    @cu)
  Mem
  (ptr [_]
    @p)
  (size [_]
    s)
  WithOffset
  (with-offset [_ byte-offset]
    (.withByteOffset ^CUdeviceptr @cu ^long byte-offset))
  (memcpy-host* [this host byte-size]
    (with-check (JCudaDriver/cuMemcpyDtoH (host-ptr host) @cu byte-size) host))
  (memcpy-host* [this host byte-size hstream]
    (with-check (JCudaDriver/cuMemcpyDtoHAsync (host-ptr host) @cu byte-size hstream) host)))

(defn cu-linear-memory
  ([^CUdeviceptr cu ^long size ^Boolean master]
   (let [cu-arr (make-array CUdeviceptr 1)]
     (aset ^"[Ljcuda.driver.CUdeviceptr;" cu-arr 0 cu)
     (->CULinearMemory (volatile! cu) (volatile! (Pointer/to ^"[Ljcuda.driver.CUdeviceptr;" cu-arr))
                       size master)))
  ([^CUdeviceptr cu ^long size]
   (cu-linear-memory cu size true)))

(defn mem-alloc-managed*
  "Allocates the `size` bytes of memory that will be automatically managed by the Unified Memory
  system, specified by an integer `flag`.

  Returns a [[CULinearmemory]] object.
  The memory is not cleared. `size` must be greater than `0`.

  See [cuMemAllocManaged](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__MEM.html).
  "
  ([^long size ^long flag]
   (let [cu (CUdeviceptr.)]
     (with-check (JCudaDriver/cuMemAllocManaged cu size flag) (cu-linear-memory cu size)))))

;; =================== Pinned Memory ================================================

(defn free-pinned [hp buf]
  (with-check (JCudaDriver/cuMemFreeHost hp) (release buf)))

(defn unregister-pinned [hp _]
  (with-check (JCudaDriver/cuMemHostUnregister hp) true))

(deftype CUPinnedMemory [cu p hp buf ^long s release-fn]
  Releaseable
  (release [this]
    (locking hp
      (when-let [h @hp]
        (locking h
          (release-fn h @buf)
          (vreset! cu nil)
          (vreset! p nil)
          (vreset! hp nil)
          (vreset! buf nil))))
    true)
  Wrapper
  (extract [_]
    @cu)
  HostMem
  (host-ptr [_]
    @hp)
  (host-buffer [_]
    @buf)
  Mem
  (ptr [_]
    @p)
  (size [_]
    s)
  WithOffset
  (with-offset [cu byte-offset]
    (.withByteOffset ^CUdeviceptr @cu ^long byte-offset))
  (memcpy-host* [this host byte-size]
    (with-check (JCudaDriver/cuMemcpyDtoH (host-ptr host) @cu byte-size) host))
  (memcpy-host* [this host byte-size hstream]
    (with-check (JCudaDriver/cuMemcpyDtoHAsync (host-ptr host) @cu byte-size hstream) host)))

(defn ^:private cu-pinned-memory [^Pointer hp ^long size release-fn]
  (let [cu (CUdeviceptr.)]
    (with-check (JCudaDriver/cuMemHostGetDevicePointer cu hp 0)
      (let [cu-arr (make-array CUdeviceptr 1)
            buf (.order (.getByteBuffer hp 0 size) (ByteOrder/nativeOrder))]
        (aset ^"[Ljcuda.driver.CUdeviceptr;" cu-arr 0 cu)
        (->CUPinnedMemory (volatile! cu) (volatile! (Pointer/to ^"[Ljcuda.driver.CUdeviceptr;" cu-arr))
                          (volatile! hp) (volatile! buf) size release-fn)))))

(defn mem-host-alloc*
  "Allocates `size` bytes of page-locked, 'pinned' on the host, using raw integer `flags`.
  For available flags, see [constants/mem-host-alloc-flags]

  The memory is not cleared. `size` must be greater than `0`.

  See [cuMemHostAlloc](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__MEM.html).
  "
  [^long size ^long flags]
  (let [p (Pointer.)]
    (with-check (JCudaDriver/cuMemHostAlloc p size flags) (cu-pinned-memory p size free-pinned))))

(defn mem-alloc-host*
  "Allocates `size` bytes of page-locked, 'pinned' on the host.

  The memory is not cleared. `size` must be greater than `0`.

  See [cuMemAllocHost](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__MEM.html).
  "
  [^long size]
  (let [p (Pointer.)]
    (with-check (JCudaDriver/cuMemAllocHost p size) (cu-pinned-memory p size free-pinned))))

(defn mem-host-register*
  "Registers previously allocated Java `memory` structure and pins it, using raw integer `flags`.

   See [cuMemHostRegister](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__MEM.html).
  "
  [memory ^long flags]
  (let [p ^Pointer (ptr memory)
        byte-size (size memory)]
    (with-check (JCudaDriver/cuMemHostRegister p byte-size flags)
      (cu-pinned-memory p byte-size unregister-pinned))))

;; =============== Host memory  =================================

(extend-type Float
  Mem
  (ptr [this]
    (ptr (wrap-float this)))
  (size [this]
    Float/BYTES))

(extend-type Double
  Mem
  (ptr [this]
    (ptr (wrap-double this)))
  (size [this]
    Double/BYTES))

(extend-type Integer
  Mem
  (ptr [this]
    (ptr (wrap-int this)))
  (size [this]
    Integer/BYTES))

(extend-type Long
  Mem
  (ptr [this]
    (ptr (wrap-long this)))
  (size [this]
    Long/BYTES))

(defmacro ^:private extend-mem-array [type atype bytes]
  `(extend-type ~type
    HostMem
    (host-ptr [this#]
      (ptr this#))
    Mem
    (ptr [this#]
      (Pointer/to (~atype this#)))
    (size [this#]
      (* ~bytes (alength (~atype this#))))
    (memcpy-host*
      ([this# cu# byte-size#]
       (with-check (JCudaDriver/cuMemcpyHtoD (extract cu#) (ptr this#) byte-size#) cu#))
      ([this# cu# byte-size# hstream#]
       (with-check (JCudaDriver/cuMemcpyHtoDAsync (extract cu#) (ptr this#) byte-size# hstream#) cu#)))))

(extend-mem-array (Class/forName "[F") floats Float/BYTES)
(extend-mem-array (Class/forName "[D") doubles Double/BYTES)
(extend-mem-array (Class/forName "[I") ints Integer/BYTES)
(extend-mem-array (Class/forName "[J") longs Long/BYTES)
(extend-mem-array (Class/forName "[B") bytes 1)
(extend-mem-array (Class/forName "[S") shorts Short/BYTES)
(extend-mem-array (Class/forName "[C") chars Character/BYTES)

(extend-type ByteBuffer
  HostMem
  (host-ptr [this]
    (ptr this))
  Mem
  (ptr [this]
    (Pointer/toBuffer this))
  (size [this]
    (.capacity ^ByteBuffer this))
  (memcpy-host*
    ([this cu byte-size]
     (with-check (JCudaDriver/cuMemcpyHtoD (extract cu) (ptr this) byte-size) cu))
    ([this cu byte-size hstream]
     (with-check (JCudaDriver/cuMemcpyHtoDAsync (extract cu) (ptr this) byte-size hstream) cu))))

;; ================== Module Management =====================================

(extend-protocol JITOption
  Integer
  (put-jit-option [value option options]
    (.putInt ^JITOptions options option value))
  Long
  (put-jit-option [value option options]
    (.putInt ^JITOptions options option value))
  Float
  (put-jit-option [value option options]
    (.putFloat ^JITOptions options option value))
  Double
  (put-jit-option [value option options]
    (.putFloat ^JITOptions options option value))
  nil
  (put-jit-option [value option options]
    (.put ^JITOptions options option)))

(defn enc-jit-options [options]
  (let [res (JITOptions.)]
    (doseq [[option value] options]
      (put-jit-option value (or (jit-options option)
                                (throw (ex-info "Unknown jit option."
                                                {:option option :available jit-options})))
                      res))
    res))

(defn link-add-data* [link-state type data name options]
  (let [type (or (jit-input-types type)
                 (throw (ex-info "Invalid jit input type." {:type type :available jit-input-types})))]
    (with-check (JCudaDriver/cuLinkAddData link-state type (ptr data) (size data) name
                                           (enc-jit-options options))
      {:data data}
      link-state)))

(defn link-add-file* [link-state type file-name options]
  (let [type (or (jit-input-types type)
                 (throw (ex-info "Invalid jit input type." {:type type :available jit-input-types})))]
    (with-check (JCudaDriver/cuLinkAddFile link-state type file-name
                                           (enc-jit-options options))
      {:file file-name}
      link-state)))

(defn link*
  "Invokes CUDA linker on data provided as a vector `[[type source <options> <name>], ...]`.
  Produces a cubin compiled for particular architecture

  See [cuLinkCreate](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__MODULE.html) and
  related `likadd` functions.
  "[^CUlinkState link-state data options]
  (with-check (JCudaDriver/cuLinkCreate (enc-jit-options options) link-state)
    (do
      (doseq [[type d options name] data]
        (if name
          (link-add-data* link-state type d name options)
          (link-add* d link-state type options)))
      link-state)))

(defn link-complete* [link-state]
  (let [cubin-image (Pointer.)]
    (with-check (JCudaDriver/cuLinkComplete link-state cubin-image (long-array 1)) cubin-image)))

(extend-type (Class/forName "[B")
  ModuleLoad
  (module-load* [binary m]
    (with-check (JCudaDriver/cuModuleLoadFatBinary ^CUmodule m ^bytes binary) {:module m} m))
  JITOption
  (put-jit-option [value option options]
    (.putBytes ^JITOptions options option value)))

(extend-type String
  ModuleLoad
  (module-load* [data m]
    (with-check (JCudaDriver/cuModuleLoadData ^CUmodule m data) {:data data} m))
  (link-add* [data link-state type options]
    (let [data-bytes (.getBytes data)
          data-image (Arrays/copyOf data-bytes (inc (alength data-bytes)))]
      (link-add-data* link-state type data-image "unnamed" options))))

(extend-type Pointer
  ModuleLoad
  (module-load* [data m]
    (with-check (JCudaDriver/cuModuleLoadDataJIT ^CUmodule m data (enc-jit-options {}))
      {:data data} m)))

(extend-type Path
  ModuleLoad
  (module-load* [file-path m]
    (let [file-name (.toString file-path)]
      (with-check (JCudaDriver/cuModuleLoad ^CUmodule m file-name) {:file file-name} m)))
  (link-add* [file-path link-state type options]
    (link-add-file* link-state type (.toString file-path) options)))

(extend-type File
  ModuleLoad
  (module-load* [file m]
    (let [file-name (.toString file)]
      (with-check (JCudaDriver/cuModuleLoad ^CUmodule m file-name) {:file file-name} m)))
  (link-add* [file link-state type options]
    (link-add-file* link-state type (.toString file) options)))

(defn module-load-data-jit*
  "Load a module's data from a [[ntrtc/ptx]] string, `nvrtcProgram`, java path, or a binary `data`,
  for an already existing module.

  See [cuModuleGetFunction](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__MODULE.html)
  "
  ([^CUmodule m ^Pointer data ^JITOptions options]
   (with-check (JCudaDriver/cuModuleLoadDataJIT ^CUmodule m data options) m)))

(defn global*
  "Returns CUDA global [[CULinearMemory]] named `name` from module `m`, with optionally specified size.

  See [cuModuleGetFunction](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__MODULE.html)
  "
  [^CUmodule m name]
  (let [res (CUdeviceptr.)
        byte-size (long-array 1)]
    (with-check
      (JCudaDriver/cuModuleGetGlobal res byte-size m name)
      {:name name}
      (cu-linear-memory res (aget byte-size 0) false))))

;; ================== Stream Management ======================================

(defn stream*
  "Create a stream using an optional `priority` and an integer `flag`.

  See [cuStreamCreate](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__STREAM.html)
  "
  ([^long flag]
   (let [res (CUstream.)]
     (with-check (JCudaDriver/cuStreamCreate res flag) res)))
  ([^long priority ^long flag]
   (let [res (CUstream.)]
     (with-check (JCudaDriver/cuStreamCreateWithPriority res flag priority) res))))

(defn ready*
  "Determines status (ready or not) of a compute stream or event.

  See [cuStreamQuery](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__STREAM.html),
  and [cuEventQuery](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__EVENT.html)
"
  [obj]
  (= CUresult/CUDA_SUCCESS (case (class obj)
                             CUstream (JCudaDriver/cuStreamQuery obj)
                             CUevent (JCudaDriver/cuEventQuery obj)
                             CUresult/CUDA_ERROR_NOT_READY)))

(defn synchronize*
  "Block for the current context's or `stream`'s tasks to complete."
  ([]
   (with-check (JCudaDriver/cuCtxSynchronize) true))
  ([^CUstream hstream]
   (with-check (JCudaDriver/cuStreamSynchronize hstream) hstream)))

(defrecord StreamCallbackInfo [status data])

(deftype StreamCallback [ch]
  CUstreamCallback
  (call [this hstream status data]
    (go (>! ch (->StreamCallbackInfo (CUresult/stringFor status) data)))))

(defn add-callback*
  "Adds a [[StreamCallback]] to a compute stream, with optional `data` related to the call.

  See [cuStreamAddCallback](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__STREAM.html)"
  [^CUstream hstream ^StreamCallback callback data]
  (with-check (JCudaDriver/cuStreamAddCallback hstream callback data 0) hstream))

(defn wait-event*
  "Makes a compute stream `hstream` wait on an event `ev`

  See [cuStreamWaitEvent](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__STREAM.html)"
  [^CUstream hstream ^CUevent ev]
  (with-check (JCudaDriver/cuStreamWaitEvent hstream ev 0) hstream))

;; ================== Event Management =======================================

(defn event*
  "Creates an event specified by integer `flags`.

  See [cuEventCreate](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__EVENT.html)
  "
  [^long flags]
  (let [res (CUevent.)]
    (with-check (JCudaDriver/cuEventCreate res flags) res)))

(defn elapsed-time*
  "Computes the elapsed time in milliseconds between `start-event` and `end-event`.

  See [cuEventElapsedTime](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__EVENT.html)
  "
  ^double [^CUevent start-event ^CUevent end-event]
  (let [res (float-array 1)]
    (with-check (JCudaDriver/cuEventElapsedTime res start-event end-event) (aget res 0))))

(defn record*
  "Records an event `ev` on optional `stream`.

  See [cuEventRecord](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__EVENT.html)
  "
  ([^CUstream stream ^CUevent event]
   (with-check (JCudaDriver/cuEventRecord event stream) stream))
  ([^CUevent event]
   (with-check (JCudaDriver/cuEventRecord event nil) nil)))

;; ================== Peer Context Memory Access =============================

(defn p2p-attribute*
  "Queries attributes of the link between two devices.

  See [cuDeviceGetP2PAttribute](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__PEER__ACCESS.html)
  "
  [dev peer ^long attribute]
  (let [res (int-array 1)]
    (with-check (JCudaDriver/cuDeviceGetP2PAttribute res attribute dev peer) (pos? (aget res 0)))))

(defn disable-peer-access*
  "Disables direct access to memory allocations in a peer context and unregisters
  any registered allocations.

  See [cuCtxDisablePeerAccess](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__PEER__ACCESS.html)
  "
  [ctx]
  (let [res (int-array 1)]
    (with-check (JCudaDriver/cuCtxDisablePeerAccess ctx) ctx)))

(defn enable-peer-access*
  "Enables direct access to memory allocations in a peer context and unregisters
  any registered allocations.

  See [cuCtxEnablePeerAccess](http://docs.nvidia.com/cuda/cuda-driver-api/group__CUDA__PEER__ACCESS.html)
  "
  [ctx]
  (let [res (int-array 1)]
    (with-check (JCudaDriver/cuCtxEnablePeerAccess ctx 0) ctx)))
