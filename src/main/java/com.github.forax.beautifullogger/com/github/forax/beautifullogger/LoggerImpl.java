package com.github.forax.beautifullogger;

import static com.github.forax.beautifullogger.LoggerImpl.LoggerConfigFeature.ENABLE_CONF;
import static com.github.forax.beautifullogger.LoggerImpl.LoggerConfigFeature.LEVEL_CONF;
import static com.github.forax.beautifullogger.LoggerImpl.LoggerConfigFeature.PRINTFACTORY_CONF;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.empty;
import static java.lang.invoke.MethodHandles.exactInvoker;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodHandles.guardWithTest;
import static java.lang.invoke.MethodHandles.identity;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.lookup;
import static java.lang.invoke.MethodHandles.publicLookup;
import static java.lang.invoke.MethodType.genericMethodType;
import static java.lang.invoke.MethodType.methodType;
import static java.util.Collections.nCopies;
import static java.util.Map.entry;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.SwitchPoint;
import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import com.github.forax.beautifullogger.Logger.Level;
import com.github.forax.beautifullogger.LoggerConfig.ConfigOption;
import com.github.forax.beautifullogger.LoggerConfig.PrintFactory;

import sun.misc.Unsafe;

class LoggerImpl {
  private static class None {
    None() { /* singleton */ }
    @Override public String toString() { return "NONE"; }
  }
  static final Object NONE = new None();
  
  // used internally by Logger, should not be visible
  static final Consumer<ConfigOption> EMPTY_CONSUMER = __ -> { /* empty */ };
  
  private LoggerImpl() {
    throw new AssertionError();
  }
  
  static MethodHandle getLoggingMethodHandle(Class<?> configClass, int maxParameter) {
    return new CS(configClass, maxParameter).dynamicInvoker();
  }
  
  private static class CS extends MutableCallSite {
    private static final MethodHandle FALLBACK;
    private static final MethodHandle[] CHECK_LEVELS;
    static {
      Lookup lookup = lookup();
      try {
        FALLBACK = lookup.findVirtual(CS.class, "fallback", methodType(MethodHandle.class, Level.class,  Throwable.class, Object.class, Object[].class));
        
        MethodHandle[] checkLevels = new MethodHandle[Level.LEVELS.length];
        for(int i = 0; i < checkLevels.length; i++) {
          checkLevels[i] = lookup.findStatic(CS.class, "checkLevel" + Level.LEVELS[i].name(), methodType(boolean.class, Level.class));
        }
        CHECK_LEVELS = checkLevels;
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }
    
    private final int maxParameters;
    private final Class<?> configClass;
    private final MethodHandle fallback;

    CS(Class<?> configClass, int maxParameters) {
      super(methodType(void.class, Level.class, Throwable.class, Object.class).appendParameterTypes(nCopies(maxParameters, Object.class)));
      this.maxParameters = maxParameters;
      this.configClass = configClass;
      MethodHandle fallback = foldArguments(exactInvoker(type()), FALLBACK.bindTo(this).asCollector(Object[].class, maxParameters));
      this.fallback = fallback;
      setTarget(fallback);
    }
    
    @SuppressWarnings("unused")
    private MethodHandle fallback(Level level, Throwable context, Object messageProvider, Object[] args) {
      Objects.requireNonNull(level, "level is null");
      Objects.requireNonNull(messageProvider, "message provider is null");      
      
      // check configuration flag 'enable'
      LinkedHashSet<SwitchPoint> switchPoints = new LinkedHashSet<>();
      boolean enable = ENABLE_CONF.findValueAndCollectSwitchPoints(configClass, switchPoints).orElse(true);
      
      MethodHandle target;
      MethodHandle empty = empty(type());
      if (enable) {
        // get configuration 'printFactory' 
        PrintFactory printFactory = PRINTFACTORY_CONF.findValueAndCollectSwitchPoints(configClass, switchPoints)
            .orElseGet(() -> PrintFactory.systemLogger());
        MethodHandle print = dropArguments(
            printFactory.getPrintMethodHandle(configClass),
            3, nCopies(1 + maxParameters, Object.class));
        
        // create the message provider call site, we already have the arguments of the first call here,
        // so we can directly call the fallback to avoid an unnecessary round trip 
        MessageProviderCS providerCallSite = new MessageProviderCS(type(), maxParameters, print); 
        providerCallSite.fallback(messageProvider, args);
        target = providerCallSite.getTarget();
        
        // check configuration level
        Level configLevel = LEVEL_CONF.findValueAndCollectSwitchPoints(configClass, switchPoints).orElse(Level.INFO);
        target = guardWithTest(CHECK_LEVELS[configLevel.ordinal()], target, empty);
        
      } else {
        // if disable, do nothing !
        target = empty;
      }
      
      // avoid recursion (i.e. non progression) if the switch points are invalidated
      // between the time the configuration is read and the time the method handle is installed
      MethodHandle result = target;
      
      // prepend switch points
      for(SwitchPoint switchPoint: switchPoints) {
        target = switchPoint.guardWithTest(target, fallback);
      }
      
      setTarget(target);
      return result;
    }
    
    
    // use one method checkLevel* by level to allow JITs to remove those checks 
    
    @SuppressWarnings("unused")
    private static boolean checkLevelTRACE(Level level) {
      return true;
    }
    @SuppressWarnings("unused")
    private static boolean checkLevelDEBUG(Level level) {
      if (level == Level.TRACE) {
        return false;
      }
      return true;
    }
    @SuppressWarnings("unused")
    private static boolean checkLevelINFO(Level level) {
      if (level == Level.TRACE) {
        return false;
      }
      if (level == Level.DEBUG) {
        return false;
      }
      return true;
    }
    @SuppressWarnings("unused")
    private static boolean checkLevelWARNING(Level level) {
      if (level  == Level.ERROR) {
        return true;
      }
      if (level == Level.WARNING) {
        return true;
      }
      return false;
    }
    @SuppressWarnings("unused")
    private static boolean checkLevelERROR(Level level) {
      if (level == Level.ERROR) {
        return true;
      }
      return false;
    }
  }
  
  private static class MessageProviderCS extends MutableCallSite {
    private static final MethodHandle FALLBACK, IS_INSTANCE;
    static {
      Lookup lookup = lookup();
      try {
        FALLBACK = dropArguments(
            lookup.findVirtual(MessageProviderCS.class, "fallback", methodType(MethodHandle.class, Object.class, Object[].class)),
            0, Level.class, Throwable.class);
        IS_INSTANCE = lookup.findVirtual(Class.class, "isInstance", methodType(boolean.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }
    
    private final int maxParameters;
    private final MethodHandle print;
    
    MessageProviderCS(MethodType type, int maxParameters, MethodHandle print) {
      super(type);
      this.maxParameters = maxParameters;
      this.print = print;
      setTarget(foldArguments(
          exactInvoker(type()),
          insertArguments(FALLBACK, 2, this).asCollector(Object[].class, maxParameters)));
    }
    
    MethodHandle fallback(Object messageProvider, Object[] args) {
      Entry<Class<?>, MethodHandle> pair = findFunctionalInterfaceMH(messageProvider);
      Class<?> providerClass = pair.getKey();
      MethodHandle provider = pair.getValue();

      // check if the provider parameter count and the actual number of arguments match
      int actualSize = findActualSize(args);
      int providerArgumentCount = provider.type().parameterCount() - 1;
      if (actualSize != providerArgumentCount) {
        throw new IllegalArgumentException("call mismatch, actual argument count " + actualSize +
            " for method type " + provider.type().dropParameterTypes(0, 1));
      }
      
      // align signature of the provider with the log signature 
      if (providerArgumentCount != maxParameters) {
        provider = dropArguments(provider, provider.type().parameterCount(), nCopies(maxParameters - providerArgumentCount, Object.class));
      }
      provider = provider.asType(genericMethodType(provider.type().parameterCount()).changeReturnType(String.class));
      provider = dropArguments(provider, 0, Level.class, Throwable.class);
      
      // fold !
      MethodHandle target = foldArguments(print, provider);
      
      // create the inlining cache
      MethodHandle guard = guardWithTest(
          dropArguments(IS_INSTANCE.bindTo(providerClass), 0, Level.class, Throwable.class),
          target,
          new MessageProviderCS(type(), maxParameters, print).dynamicInvoker());
      setTarget(guard);
      
      return target;
    }
  }
  
  
  private static final List<Entry<Class<?>, MethodHandle>> MESSAGE_PROVIDERS = List.of(
      findVirtualMethod(Supplier.class,       "get",   methodType(String.class)),
      findVirtualMethod(IntFunction.class,    "apply", methodType(String.class, int.class)),
      findVirtualMethod(LongFunction.class,   "apply", methodType(String.class, long.class)),
      findVirtualMethod(DoubleFunction.class, "apply", methodType(String.class, double.class)),
      findVirtualMethod(Function.class,       "apply", methodType(String.class, Object.class)),
      findVirtualMethod(BiFunction.class,     "apply", methodType(String.class, Object.class, Object.class)),
      entry(String.class, identity(Object.class).asType(methodType(String.class, Object.class))));
  
  private static Entry<Class<?>, MethodHandle> findVirtualMethod(Class<?> fun, String name, MethodType type) {
    MethodHandle mh;
    try {
      mh = publicLookup().findVirtual(fun, name, type.erase());
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
    return entry(fun, mh.asType(type.insertParameterTypes(0, fun)));
  }
  
  static Entry<Class<?>, MethodHandle> findFunctionalInterfaceMH(Object messageProvider) {
    return MESSAGE_PROVIDERS.stream()
        .filter(entry -> entry.getKey().isInstance(messageProvider))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unknown message provider type " + messageProvider.getClass()));
  }
  
  static int findActualSize(Object[] args) {
    for(int i = args.length; --i >= 0;) {
      if (args[i] != NONE) {
        return i + 1;
      }
    }
    return 0;
  }
  
  static UndeclaredThrowableException rethrow(Throwable e) {
    if (e instanceof RuntimeException) {
      throw (RuntimeException)e;
    }
    if (e instanceof Error) {
      throw (Error)e;
    }
    return new UndeclaredThrowableException(e);
  }
  
  enum LoggerConfigKind {
    CLASS(Class::getName),
    PACKAGE(Class::getPackageName),
    MODULE(type -> type.getModule().getName());
    
    private final Function<Class<?>, String> nameExtractor;
    
    private LoggerConfigKind(Function<Class<?>, String> nameExtractor) {
      this.nameExtractor = nameExtractor;
    }
    
    String key(String name) {
      return name() + ';' + name;
    }
    
    String extractNameFromClass(Class<?> type) {
      return nameExtractor.apply(type);
    }
    
    static final LoggerConfigKind[] VALUES = values();
  }
  
  static class LoggerConfigFeature<T> {
    static final LoggerConfigFeature<Boolean> ENABLE_CONF = new LoggerConfigFeature<>(LoggerConfig::enable);
    static final LoggerConfigFeature<Level> LEVEL_CONF = new LoggerConfigFeature<>(LoggerConfig::level);
    static final LoggerConfigFeature<PrintFactory> PRINTFACTORY_CONF = new LoggerConfigFeature<>(LoggerConfig::printFactory);
    
    private final Function<LoggerConfigImpl, Optional<T>> extractor;
    
    private LoggerConfigFeature(Function<LoggerConfigImpl, Optional<T>> extractor) {
      this.extractor = extractor;
    }
    
    Optional<T> findValueAndCollectSwitchPoints(Class<?> type, Set<SwitchPoint> switchPoints) {
      for(LoggerConfigKind kind: LoggerConfigKind.VALUES) {
        String name = kind.extractNameFromClass(type);
        LoggerConfigImpl loggerConfig = configFrom(kind, name);
        switchPoints.add(loggerConfig.switchPoint());
        Optional<T> value = extractor.apply(loggerConfig);
        if (value.isPresent()) {
          return value;
        }
      }
      return Optional.empty();
    }
  }
  
  static class LoggerConfigImpl implements LoggerConfig {
    class ConfigOptionImpl implements ConfigOption {
      @Override
      public ConfigOption enable(boolean enable) {
        LoggerConfigImpl.this.enable = enable;
        return this;
      } 
      @Override
      public ConfigOption level(Level level) {
        LoggerConfigImpl.this.level = Objects.requireNonNull(level);
        return this;
      }
      @Override
      public ConfigOption printFactory(PrintFactory printFactory) {
        LoggerConfigImpl.this.printFactory = Objects.requireNonNull(printFactory);
        return this;
      }
    }
    
    private final Object lock = new Object();
    private SwitchPoint switchPoint;
    
    volatile Boolean enable; // nullable
    volatile Level level;    // nullable
    volatile PrintFactory printFactory;  // nullable

    LoggerConfigImpl() {
      synchronized(lock) {
        this.switchPoint = new SwitchPoint();
      }
    }

    @Override
    public Optional<Boolean> enable() {
      return Optional.ofNullable(enable);
    }
    @Override
    public Optional<Level> level() {
      return Optional.ofNullable(level);
    }
    @Override
    public Optional<PrintFactory> printFactory() {
      return Optional.ofNullable(printFactory);
    }
    
    SwitchPoint switchPoint() {
      synchronized (lock) {
        return switchPoint;  
      }
    }
    
    @Override
    public LoggerConfig update(Consumer<? super ConfigOption> configUpdater) {
      synchronized(lock) {
        configUpdater.accept(new ConfigOptionImpl());
        SwitchPoint.invalidateAll(new SwitchPoint[] { switchPoint });
        switchPoint = new SwitchPoint();
      }
      return this;
    }
  }
  
  private final static ConcurrentHashMap<String, LoggerConfigImpl> CONFIG =
      new ConcurrentHashMap<>();
  
  static LoggerConfigImpl configFrom(LoggerConfigKind kind, String name) {
    return CONFIG.computeIfAbsent(kind.key(name), __ ->  new LoggerConfigImpl());
  }
  
  private static final MethodHandle LOGGER_FACTORY;
  static {
    String data = "yv66vgAAADUAWQEALGNvbS9naXRodWIvZm9yYXgvYmVhdXRpZnVsbG9nZ2VyL0xvZ2dlciRTdHViBwABAQAQamF2YS9sYW5nL09iamVjdAcAAwEAJ2NvbS9naXRodWIvZm9yYXgvYmVhdXRpZnVsbG9nZ2VyL0xvZ2dlcgcABQEAAm1oAQAfTGphdmEvbGFuZy9pbnZva2UvTWV0aG9kSGFuZGxlOwEABjxpbml0PgEAIihMamF2YS9sYW5nL2ludm9rZS9NZXRob2RIYW5kbGU7KVYBAAMoKVYMAAkACwoABAAMDAAHAAgJAAIADgEABmNyZWF0ZQEASihMamF2YS9sYW5nL2ludm9rZS9NZXRob2RIYW5kbGU7KUxjb20vZ2l0aHViL2ZvcmF4L2JlYXV0aWZ1bGxvZ2dlci9Mb2dnZXI7DAAJAAoKAAIAEgEABWVycm9yAQAqKExqYXZhL2xhbmcvU3RyaW5nO0xqYXZhL2xhbmcvVGhyb3dhYmxlOylWAQAkTGphdmEvbGFuZy9pbnZva2UvTGFtYmRhRm9ybSRIaWRkZW47AQAoTGpkay9pbnRlcm5hbC92bS9hbm5vdGF0aW9uL0ZvcmNlSW5saW5lOwEALWNvbS9naXRodWIvZm9yYXgvYmVhdXRpZnVsbG9nZ2VyL0xvZ2dlciRMZXZlbAcAGAEABUVSUk9SAQAvTGNvbS9naXRodWIvZm9yYXgvYmVhdXRpZnVsbG9nZ2VyL0xvZ2dlciRMZXZlbDsMABoAGwkAGQAcAQArY29tL2dpdGh1Yi9mb3JheC9iZWF1dGlmdWxsb2dnZXIvTG9nZ2VySW1wbAcAHgEABE5PTkUBABJMamF2YS9sYW5nL09iamVjdDsMACAAIQkAHwAiAQAdamF2YS9sYW5nL2ludm9rZS9NZXRob2RIYW5kbGUHACQBAAtpbnZva2VFeGFjdAEAoShMY29tL2dpdGh1Yi9mb3JheC9iZWF1dGlmdWxsb2dnZXIvTG9nZ2VyJExldmVsO0xqYXZhL2xhbmcvVGhyb3dhYmxlO0xqYXZhL2xhbmcvT2JqZWN0O0xqYXZhL2xhbmcvT2JqZWN0O0xqYXZhL2xhbmcvT2JqZWN0O0xqYXZhL2xhbmcvT2JqZWN0O0xqYXZhL2xhbmcvT2JqZWN0OylWDAAmACcKACUAKAEAIChMamF2YS91dGlsL2Z1bmN0aW9uL1N1cHBsaWVyOylWAQAyKExqYXZhL3V0aWwvZnVuY3Rpb24vRnVuY3Rpb247TGphdmEvbGFuZy9PYmplY3Q7KVYBACQoTGphdmEvdXRpbC9mdW5jdGlvbi9JbnRGdW5jdGlvbjtJKVYBABFqYXZhL2xhbmcvSW50ZWdlcgcALQEAB3ZhbHVlT2YBABYoSSlMamF2YS9sYW5nL0ludGVnZXI7DAAvADAKAC4AMQEAJShMamF2YS91dGlsL2Z1bmN0aW9uL0xvbmdGdW5jdGlvbjtKKVYBAA5qYXZhL2xhbmcvTG9uZwcANAEAEyhKKUxqYXZhL2xhbmcvTG9uZzsMAC8ANgoANQA3AQAnKExqYXZhL3V0aWwvZnVuY3Rpb24vRG91YmxlRnVuY3Rpb247RClWAQAQamF2YS9sYW5nL0RvdWJsZQcAOgEAFShEKUxqYXZhL2xhbmcvRG91YmxlOwwALwA8CgA7AD0BAEYoTGphdmEvdXRpbC9mdW5jdGlvbi9CaUZ1bmN0aW9uO0xqYXZhL2xhbmcvT2JqZWN0O0xqYXZhL2xhbmcvT2JqZWN0OylWAQAHd2FybmluZwEAB1dBUk5JTkcMAEEAGwkAGQBCAQAEaW5mbwEABElORk8MAEUAGwkAGQBGAQAFZGVidWcBAAVERUJVRwwASQAbCQAZAEoBAAV0cmFjZQEABVRSQUNFDABNABsJABkATgEABENvZGUBABlSdW50aW1lVmlzaWJsZUFubm90YXRpb25zAQAJU2lnbmF0dXJlAQA0KExqYXZhL3V0aWwvZnVuY3Rpb24vU3VwcGxpZXI8TGphdmEvbGFuZy9TdHJpbmc7PjspVgEAUTxUOkxqYXZhL2xhbmcvT2JqZWN0Oz4oTGphdmEvdXRpbC9mdW5jdGlvbi9GdW5jdGlvbjwtVFQ7TGphdmEvbGFuZy9TdHJpbmc7PjtUVDspVgEAOChMamF2YS91dGlsL2Z1bmN0aW9uL0ludEZ1bmN0aW9uPExqYXZhL2xhbmcvU3RyaW5nOz47SSlWAQA5KExqYXZhL3V0aWwvZnVuY3Rpb24vTG9uZ0Z1bmN0aW9uPExqYXZhL2xhbmcvU3RyaW5nOz47SilWAQA7KExqYXZhL3V0aWwvZnVuY3Rpb24vRG91YmxlRnVuY3Rpb248TGphdmEvbGFuZy9TdHJpbmc7PjtEKVYBAG48VDpMamF2YS9sYW5nL09iamVjdDtVOkxqYXZhL2xhbmcvT2JqZWN0Oz4oTGphdmEvdXRpbC9mdW5jdGlvbi9CaUZ1bmN0aW9uPC1UVDstVFU7TGphdmEvbGFuZy9TdHJpbmc7PjtUVDtUVTspVgAgAAIABAABAAYAAQASAAcACAAAACUAAgAJAAoAAQBQAAAAFgACAAIAAAAKKrcADSortQAPsQAAAAAACQAQABEAAQBQAAAAFQADAAEAAAAJuwACWSq3ABOwAAAAAAABABQAFQACAFAAAAAlAAgAAwAAABkqtAAPsgAdLCuyACOyACOyACOyACO2ACmxAAAAAABRAAAACgACABYAAAAXAAAAAQAUACoAAwBQAAAAJQAIAAIAAAAZKrQAD7IAHQErsgAjsgAjsgAjsgAjtgApsQAAAAAAUgAAAAIAUwBRAAAACgACABYAAAAXAAAAAQAUACsAAwBQAAAAIwAIAAMAAAAXKrQAD7IAHQErLLIAI7IAI7IAI7YAKbEAAAAAAFIAAAACAFQAUQAAAAoAAgAWAAAAFwAAAAEAFAAsAAMAUAAAACYACAADAAAAGiq0AA+yAB0BKxy4ADKyACOyACOyACO2ACmxAAAAAABSAAAAAgBVAFEAAAAKAAIAFgAAABcAAAABABQAMwADAFAAAAAmAAgABAAAABoqtAAPsgAdASsguAA4sgAjsgAjsgAjtgApsQAAAAAAUgAAAAIAVgBRAAAACgACABYAAAAXAAAAAQAUADkAAwBQAAAAJgAIAAQAAAAaKrQAD7IAHQErKLgAPrIAI7IAI7IAI7YAKbEAAAAAAFIAAAACAFcAUQAAAAoAAgAWAAAAFwAAAAEAFAA/AAMAUAAAACEACAAEAAAAFSq0AA+yAB0BKywtsgAjsgAjtgApsQAAAAAAUgAAAAIAWABRAAAACgACABYAAAAXAAAAAQBAABUAAgBQAAAAJQAIAAMAAAAZKrQAD7IAQywrsgAjsgAjsgAjsgAjtgApsQAAAAAAUQAAAAoAAgAWAAAAFwAAAAEAQAAqAAMAUAAAACUACAACAAAAGSq0AA+yAEMBK7IAI7IAI7IAI7IAI7YAKbEAAAAAAFIAAAACAFMAUQAAAAoAAgAWAAAAFwAAAAEAQAArAAMAUAAAACMACAADAAAAFyq0AA+yAEMBKyyyACOyACOyACO2ACmxAAAAAABSAAAAAgBUAFEAAAAKAAIAFgAAABcAAAABAEAALAADAFAAAAAmAAgAAwAAABoqtAAPsgBDASscuAAysgAjsgAjsgAjtgApsQAAAAAAUgAAAAIAVQBRAAAACgACABYAAAAXAAAAAQBAADMAAwBQAAAAJgAIAAQAAAAaKrQAD7IAQwErILgAOLIAI7IAI7IAI7YAKbEAAAAAAFIAAAACAFYAUQAAAAoAAgAWAAAAFwAAAAEAQAA5AAMAUAAAACYACAAEAAAAGiq0AA+yAEMBKyi4AD6yACOyACOyACO2ACmxAAAAAABSAAAAAgBXAFEAAAAKAAIAFgAAABcAAAABAEAAPwADAFAAAAAhAAgABAAAABUqtAAPsgBDASssLbIAI7IAI7YAKbEAAAAAAFIAAAACAFgAUQAAAAoAAgAWAAAAFwAAAAEARAAVAAIAUAAAACUACAADAAAAGSq0AA+yAEcsK7IAI7IAI7IAI7IAI7YAKbEAAAAAAFEAAAAKAAIAFgAAABcAAAABAEQAKgADAFAAAAAlAAgAAgAAABkqtAAPsgBHASuyACOyACOyACOyACO2ACmxAAAAAABSAAAAAgBTAFEAAAAKAAIAFgAAABcAAAABAEQAKwADAFAAAAAjAAgAAwAAABcqtAAPsgBHASsssgAjsgAjsgAjtgApsQAAAAAAUgAAAAIAVABRAAAACgACABYAAAAXAAAAAQBEACwAAwBQAAAAJgAIAAMAAAAaKrQAD7IARwErHLgAMrIAI7IAI7IAI7YAKbEAAAAAAFIAAAACAFUAUQAAAAoAAgAWAAAAFwAAAAEARAAzAAMAUAAAACYACAAEAAAAGiq0AA+yAEcBKyC4ADiyACOyACOyACO2ACmxAAAAAABSAAAAAgBWAFEAAAAKAAIAFgAAABcAAAABAEQAOQADAFAAAAAmAAgABAAAABoqtAAPsgBHASsouAA+sgAjsgAjsgAjtgApsQAAAAAAUgAAAAIAVwBRAAAACgACABYAAAAXAAAAAQBEAD8AAwBQAAAAIQAIAAQAAAAVKrQAD7IARwErLC2yACOyACO2ACmxAAAAAABSAAAAAgBYAFEAAAAKAAIAFgAAABcAAAABAEgAFQACAFAAAAAlAAgAAwAAABkqtAAPsgBLLCuyACOyACOyACOyACO2ACmxAAAAAABRAAAACgACABYAAAAXAAAAAQBIACoAAwBQAAAAJQAIAAIAAAAZKrQAD7IASwErsgAjsgAjsgAjsgAjtgApsQAAAAAAUgAAAAIAUwBRAAAACgACABYAAAAXAAAAAQBIACsAAwBQAAAAIwAIAAMAAAAXKrQAD7IASwErLLIAI7IAI7IAI7YAKbEAAAAAAFIAAAACAFQAUQAAAAoAAgAWAAAAFwAAAAEASAAsAAMAUAAAACYACAADAAAAGiq0AA+yAEsBKxy4ADKyACOyACOyACO2ACmxAAAAAABSAAAAAgBVAFEAAAAKAAIAFgAAABcAAAABAEgAMwADAFAAAAAmAAgABAAAABoqtAAPsgBLASsguAA4sgAjsgAjsgAjtgApsQAAAAAAUgAAAAIAVgBRAAAACgACABYAAAAXAAAAAQBIADkAAwBQAAAAJgAIAAQAAAAaKrQAD7IASwErKLgAPrIAI7IAI7IAI7YAKbEAAAAAAFIAAAACAFcAUQAAAAoAAgAWAAAAFwAAAAEASAA/AAMAUAAAACEACAAEAAAAFSq0AA+yAEsBKywtsgAjsgAjtgApsQAAAAAAUgAAAAIAWABRAAAACgACABYAAAAXAAAAAQBMABUAAgBQAAAAJQAIAAMAAAAZKrQAD7IATywrsgAjsgAjsgAjsgAjtgApsQAAAAAAUQAAAAoAAgAWAAAAFwAAAAEATAAqAAMAUAAAACUACAACAAAAGSq0AA+yAE8BK7IAI7IAI7IAI7IAI7YAKbEAAAAAAFIAAAACAFMAUQAAAAoAAgAWAAAAFwAAAAEATAArAAMAUAAAACMACAADAAAAFyq0AA+yAE8BKyyyACOyACOyACO2ACmxAAAAAABSAAAAAgBUAFEAAAAKAAIAFgAAABcAAAABAEwALAADAFAAAAAmAAgAAwAAABoqtAAPsgBPASscuAAysgAjsgAjsgAjtgApsQAAAAAAUgAAAAIAVQBRAAAACgACABYAAAAXAAAAAQBMADMAAwBQAAAAJgAIAAQAAAAaKrQAD7IATwErILgAOLIAI7IAI7IAI7YAKbEAAAAAAFIAAAACAFYAUQAAAAoAAgAWAAAAFwAAAAEATAA5AAMAUAAAACYACAAEAAAAGiq0AA+yAE8BKyi4AD6yACOyACOyACO2ACmxAAAAAABSAAAAAgBXAFEAAAAKAAIAFgAAABcAAAABAEwAPwADAFAAAAAhAAgABAAAABUqtAAPsgBPASssLbIAI7IAI7YAKbEAAAAAAFIAAAACAFgAUQAAAAoAAgAWAAAAFwAAAAA=";
    byte[] array = Base64.getDecoder().decode(data);
    
    Lookup lookup = MethodHandles.lookup();
    try {
      Field field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      Unsafe unsafe = (Unsafe)field.get(null);
      Class<?> loggerClass = unsafe.defineAnonymousClass(Logger.class, array, null);
      LOGGER_FACTORY = lookup.findStatic(loggerClass, "create", methodType(Logger.class, MethodHandle.class));
    } catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }
  
  static Logger createLogger(MethodHandle mh) {
    try {
      return (Logger)LOGGER_FACTORY.invokeExact(mh);
    } catch (Throwable e) {
      throw rethrow(e);
    }
  }
}
