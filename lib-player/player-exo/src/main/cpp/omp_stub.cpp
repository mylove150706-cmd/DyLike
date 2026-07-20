// OpenMP stub: 替代 libomp，所有调用变成单线程空操作
// 解决荣耀设备 libomp.so __kmp_invoke_microtask SIGSEGV 问题
#include <stdint.h>

extern "C" {

// NCNN 使用的 OpenMP 符号，全部 stub 成单线程
int omp_get_max_threads() { return 1; }
int omp_get_num_threads() { return 1; }
int omp_get_thread_num() { return 0; }
void omp_set_num_threads(int) {}
void omp_set_dynamic(int) {}
int omp_get_dynamic() { return 0; }
void omp_set_nested(int) {}
int omp_get_nested() { return 0; }
double omp_get_wtime() { return 0; }
void omp_set_max_active_levels(int) {}
int omp_get_max_active_levels() { return 1; }
int omp_in_parallel() { return 0; }
void omp_set_schedule(int, int) {}
void omp_get_schedule(int*, int*) {}

// __kmp 系列（Intel OpenMP runtime）
void __kmpc_fork_call(void*, int, void*, ...) {}
void __kmpc_serialized_parallel(void*, int) {}
void __kmpc_end_serialized_parallel(void*, int) {}
int __kmpc_global_thread_num(void*) { return 0; }
void __kmpc_push_num_threads(void*, int, int) {}
int __kmpc_master(void*, int) { return 1; }
void __kmpc_end_master(void*, int) {}
void __kmpc_barrier(void*, int) {}
int __kmpc_single(void*, int) { return 1; }
void __kmpc_end_single(void*, int) {}
void __kmpc_dispatch_init_4(void*, int, int, int, int, int, int) {}
void __kmpc_dispatch_init_8(void*, int, int, long, long, long, long) {}
int __kmpc_dispatch_next_4(void*, int, int*, int*, int*, int*) { return 0; }
int __kmpc_dispatch_next_8(void*, int, int*, long*, long*, long*, long*) { return 0; }
void __kmpc_for_static_init_4(void*, int, int, int*, int*, int*, int*, int, int) {}
void __kmpc_for_static_init_4u(void*, int, int, int*, unsigned int*, unsigned int*, int*, int, int) {}
void __kmpc_for_static_init_8(void*, int, int, int*, long*, long*, long*, long, long) {}
void __kmpc_for_static_fini(void*, int) {}
void __kmpc_critical(void*, int, void*) {}
void __kmpc_end_critical(void*, int, void*) {}
void __kmpc_reduce_nowait(void*, int, int, unsigned int, void*, void*) {}
void __kmpc_end_reduce_nowait(void*, int, void*) {}
int __kmpc_reduce(void*, int, int, unsigned int, void*, void*) { return 1; }
void __kmpc_end_reduce(void*, int, void*) {}
void __kmpc_flush(char const*, ...) {}
void* __kmpc_omp_task_alloc(void*, int, int, unsigned int, unsigned int, void*) { return nullptr; }
int __kmpc_omp_task(void*, int, void*) { return 0; }
void __kmpc_taskgroup(void*, int) {}
void __kmpc_end_taskgroup(void*, int) {}
int __kmpc_omp_taskyield(void*, int, int) { return 0; }
int __kmpc_omp_taskwait(void*, int) { return 0; }
void __kmpc_yield(void*, int) {}
int kmp_get_blocktime() { return 0; }
void kmp_set_blocktime(int) {}

} // extern "C"
