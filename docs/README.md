# GPU Support Framework Documentation

Welcome to the GPU Support Framework documentation! This directory contains comprehensive guides, architecture documentation, and best practices.

## Documentation Index

### Getting Started
- **[Main README](../README.md)** - Project overview, quick start, and installation
- **[Resource Module README](../resource/README.md)** - Resource lifecycle management guide
- **[GPU Test Framework README](../gpu-test-framework/README.md)** - Testing framework overview
- **[Usage Guide](../gpu-test-framework/USAGE_GUIDE.md)** - Comprehensive usage examples

### Development
- **[CLAUDE.md](../CLAUDE.md)** - Development guide for Claude Code
- **[CONTRIBUTING.md](../CONTRIBUTING.md)** - How to contribute to the project
- **[Architecture Overview](ARCHITECTURE.md)** - Deep dive into framework architecture
- **[CI/CD Integration Guide](CI_CD_GUIDE.md)** - Setting up continuous integration

### Guides by Topic

#### Resource Management
- Memory pooling and eviction policies
- Leak detection and debugging
- Performance tuning and benchmarks
- Thread safety and concurrency

See: [Resource Module README](../resource/README.md)

#### GPU Testing
- Headless testing setup
- CI/CD compatibility
- Mock platforms for testing
- Platform-specific considerations

See: [GPU Test Framework README](../gpu-test-framework/README.md)

#### Integration
- OpenCL integration patterns
- OpenGL resource management
- Metal compute support (planned)
- Multi-backend applications

See: [Usage Guide](../gpu-test-framework/USAGE_GUIDE.md)

## Quick Links

### For New Users
1. Read the [Main README](../README.md)
2. Follow the [Quick Start](../README.md#quick-start)
3. Review the [Usage Guide](../gpu-test-framework/USAGE_GUIDE.md)
4. Check [CI/CD Guide](CI_CD_GUIDE.md) for build integration

### For Contributors
1. Read [CONTRIBUTING.md](../CONTRIBUTING.md)
2. Understand the [Architecture](ARCHITECTURE.md)
3. Follow [Coding Standards](../CONTRIBUTING.md#coding-standards)
4. Review [Testing Guidelines](../CONTRIBUTING.md#testing-guidelines)

### For Integrators
1. Review [Architecture Overview](ARCHITECTURE.md)
2. Check [Resource Module README](../resource/README.md)
3. Read [GPU Test Framework README](../gpu-test-framework/README.md)
4. Study [CI/CD Integration](CI_CD_GUIDE.md)

## Architecture Documentation

### High-Level Overview

```
GPU Support Framework
├── Resource Management (RAII-based lifecycle)
│   ├── Memory Pooling (LRU/FIFO/LFU)
│   ├── Leak Detection
│   └── Performance Monitoring
└── GPU Test Framework (CI/CD compatible)
    ├── Headless Testing
    ├── Mock Platforms
    └── Multi-Backend Support
```

For details, see [Architecture Overview](ARCHITECTURE.md)

## Common Use Cases

### Use Case 1: Adding GPU Acceleration to Your Project

1. Add dependency to `pom.xml`
2. Create resource manager
3. Implement GPU kernels
4. Add tests using `CICompatibleGPUTest`
5. Verify CI/CD compatibility

**Guide:** [Resource Module README](../resource/README.md) + [Usage Guide](../gpu-test-framework/USAGE_GUIDE.md)

### Use Case 2: Testing GPU Code in CI/CD

1. Extend `CICompatibleGPUTest`
2. Implement test methods
3. Add to CI workflow
4. Tests skip gracefully without GPU

**Guide:** [CI/CD Integration Guide](CI_CD_GUIDE.md)

### Use Case 3: Resource Leak Detection

1. Enable leak detection in configuration
2. Run tests with tracking enabled
3. Review leak reports in logs
4. Fix resource leaks

**Guide:** [Resource Module README](../resource/README.md#troubleshooting)

## API Reference

### Core Classes

**Resource Module:**
- `UnifiedResourceManager` - Central resource manager
- `ResourceHandle<T>` - Base RAII class
- `MemoryPool` - ByteBuffer pooling
- `ResourceTracker` - Leak detection

**GPU Test Framework:**
- `CICompatibleGPUTest` - CI-compatible test base
- `GPUComputeHeadlessTest` - GPU compute testing
- `MockPlatform` - Mock GPU platform
- `PlatformTestSupport` - Platform utilities

Full API documentation: [JavaDoc](https://hellblazer.github.io/gpu-support/) (coming soon)

## Best Practices

### Resource Management

**Recommended:**
- Always use try-with-resources
- Call `performMaintenance()` periodically
- Configure pool size for your workload
- Enable leak detection in development

**Avoid:**
- Forget to close resources
- Use finalize() for cleanup
- Ignore leak warnings
- Assume infinite pool size

### GPU Testing

**Recommended:**
- Extend `CICompatibleGPUTest`
- Check for mock platforms
- Use assumptions for conditional tests
- Test CPU and GPU implementations

**Avoid:**
- Assume GPU always available
- Require specific GPU hardware
- Skip CI compatibility
- Ignore test skip messages

## Troubleshooting

### Common Issues

**Issue:** "No OpenCL platforms found"
- **Solution:** Normal in CI - tests skip automatically
- **Guide:** [CI/CD Integration Guide](CI_CD_GUIDE.md#expected-behavior)

**Issue:** Resource leaks detected
- **Solution:** Enable debug logging, check allocation stacks
- **Guide:** [Resource Module README](../resource/README.md#troubleshooting)

**Issue:** Tests fail in CI
- **Solution:** Use `CICompatibleGPUTest`, check assumptions
- **Guide:** [CI/CD Integration Guide](CI_CD_GUIDE.md#debugging-ci-issues)

## Performance Tuning

### Resource Module
- Pool size: Start with 50-100MB
- Eviction policy: LRU for general use
- Idle time: 5-10 minutes typical
- Monitoring: Enable statistics in production

See: [Resource Module README](../resource/README.md#performance-considerations)

### GPU Testing
- Use parallel test execution
- Mock platform for framework tests
- Benchmark CPU vs GPU implementations
- Profile memory transfers

See: [GPU Test Framework README](../gpu-test-framework/README.md#performance-benchmarking)

## Version History

- **0.0.1-SNAPSHOT** - Initial development release
  - RAII resource management
  - CI-compatible GPU testing
  - Multi-platform support

## Future Roadmap

### Planned Features
- [ ] Vulkan API support
- [ ] Metal compute shaders
- [ ] Distributed resource management
- [ ] Advanced eviction policies
- [ ] Resource usage prediction
- [ ] WebGPU backend

See: [Main README Roadmap](../README.md#roadmap)

## Additional Resources

### External Documentation
- [LWJGL Documentation](https://www.lwjgl.org/guide)
- [OpenCL Specification](https://www.khronos.org/opencl/)
- [Java Platform Module System](https://openjdk.org/jeps/261)

### Related Projects
- [Luciferase](https://github.com/Hellblazer/Luciferase) - Parent spatial computing project
- [LWJGL](https://github.com/LWJGL/lwjgl3) - Lightweight Java Game Library

## Getting Help

- **Documentation Issues:** Open an issue on GitHub
- **Questions:** GitHub Discussions (coming soon)
- **Bugs:** GitHub Issues with reproducible example
- **Feature Requests:** GitHub Issues with use case

## Contributing to Documentation

Documentation improvements are welcome! See [CONTRIBUTING.md](../CONTRIBUTING.md#documentation) for guidelines.

### Documentation Style
- Clear, concise language
- Code examples for concepts
- Real-world use cases
- Troubleshooting sections
- Performance considerations

---

**Note:** This documentation is actively maintained. If you find errors or have suggestions, please open an issue or pull request.

Last updated: 2025-11-30
