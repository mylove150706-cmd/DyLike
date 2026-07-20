// OpenMP stub: 替代 libomp，单线程执行 parallel 区域
// 关键：__kmpc_fork_call 必须调用 microtask 函数，否则循环体不执行
#include <stdint.h>
#include <cstring>
#include <stdarg.h>

// microtask 函数类型: void(*)(int* gtid, int* nthreads, ...)
typedef void (*kmpc_micro)(int*, int*, ...);

extern "C" {

int omp_get_max_threads() { return 1; }
int omp_get_num_threads() { return 1; }
int omp_get_thread_num() { return 0; }
void omp_set_num_threads(int) {}
void omp_set_dynamic(int) {}
int omp_get_dynamic() { return 0; }
void omp_set_nested(int) {}
int omp_get_nested() { return 0; }
double omp_get_wtime();
void omp_set_max_active_levels(int) {}
int omp_get_max_active_levels() { return 1; }
int omp_in_parallel() { return 0; }
void omp_set_schedule(int, int) {}
void omp_get_schedule(int*, int*) {}

// === 关键：fork_call 必须执行 microtask ===
// NCNN 的 parallel for 通过 __kmpc_fork_call 启动并行区域
// 签名: void __kmpc_fork_call(ident*, argc, microtask, ...)
// microtask 会内部调用 __kmpc_for_static_init/next 来分配迭代
void __kmpc_fork_call(void* loc, int argc, kmpc_micro microtask, ...) {
    int gtid = 0;
    int nthreads = 1;
    // 把可变参数传给 microtask
    // microtask 的前两个参数是 gtid 和 nthreads，后面是 captured variables
    // 由于 C 可变参数传递复杂，直接用汇编级别调用更可靠
    // 简化方案：microtask 的参数通过栈传递，我们手动构造
    va_list args;
    va_start(args, microtask);

    // microtask(int* gtid, int* nthreads, captured...)
    // 我们不能安全地传递任意数量的 captured 变量
    // 但 NCNN 的 parallel for 通常用 shared 变量（全局/堆上的）
    // microtask 内部会调用 __kmpc_for_static_init 来获得循环范围

    // 直接调用 microtask，传入 gtid 和 nthreads
    // 注意：这假设 microtask 只用到前两个参数 + shared 变量
    microtask(&gtid, &nthreads, args);  // args 作为剩余参数
    va_end(args);
}

void __kmpc_serialized_parallel(void*, int) {}
void __kmpc_end_serialized_parallel(void*, int) {}
int __kmpc_global_thread_num(void*) { return 0; }
void __kmpc_push_num_threads(void*, int, int) {}
int __kmpc_master(void*, int) { return 1; }
void __kmpc_end_master(void*, int) {}
void __kmpc_barrier(void*, int) {}
int __kmpc_single(void*, int) { return 1; }
void __kmpc_end_single(void*, int) {}

// === for_static_init/next: 单线程分配全部迭代 ===
// NCNN 用这些来分配 parallel for 的迭代范围
// 单线程模式：lower=0, upper=原upper, stride=1
void __kmpc_for_static_init_4(void* loc, int gtid, int schedtype,
    int* plastiter, int* plower, int* pupper, int* pstride,
    int incr, int chunk) {
    *plastiter = 1;  // 单线程 = 最后一次迭代
    // plower/pupper 已经被调用者设置，不需要修改
    *pstride = incr;
}

void __kmpc_for_static_init_4u(void* loc, int gtid, int schedtype,
    int* plastiter, unsigned int* plower, unsigned int* pupper, int* pstride,
    int incr, int chunk) {
    *plastiter = 1;
    *pstride = incr;
}

void __kmpc_for_static_init_8(void* loc, int gtid, int schedtype,
    int* plastiter, long* plower, long* pupper, long* pstride,
    long incr, long chunk) {
    *plastiter = 1;
    *pstride = incr;
}

void __kmpc_for_static_fini(void*, int) {}

void __kmpc_critical(void*, int, void*) {}
void __kmpc_end_critical(void*, int, void*) {}
void __kmpc_reduce_nowait(void*, int, int, unsigned int, void*, void*) {}
void __kmpc_end_reduce_nowait(void*, int, void*) {}
int __kmpc_reduce(void*, int, int, unsigned int, void*, void*) { return 1; }
void __kmpc_end_reduce(void*, int, void*) {}
void __kmpc_flush(char const*, ...) {}

// task 相关（NCNN 不用，stub 空操作）
void* __kmpc_omp_task_alloc(void*, int, int, unsigned int, unsigned int, void*) { return nullptr; }
int __kmpc_omp_task(void*, int, void*) { return 0; }
void __kmpc_taskgroup(void*, int) {}
void __kmpc_end_taskgroup(void*, int) {}
int __kmpc_omp_taskyield(void*, int, int) { return 0; }
int __kmpc_omp_taskwait(void*, int) { return 0; }
void __kmpc_yield(void*, int) {}

int kmp_get_blocktime() { return 0; }
void kmp_set_blocktime(int) {}

// dispatch（NCNN 可能用）
void __kmpc_dispatch_init_4(void*, int, int, int, int, int, int) {}
void __kmpc_dispatch_init_8(void*, int, int, long, long, long, long) {}
int __kmpc_dispatch_next_4(void*, int, int*, int*, int*, int*) { return 0; }
int __kmpc_dispatch_next_8(void*, int, int*, long*, long*, long*, long*) { return 0; }

} // extern "C"
