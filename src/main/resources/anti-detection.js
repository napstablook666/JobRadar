(() => {
    "use strict";

    /* -------------------------------------------------------
     * Boss 主路径反检测（PlaywrightManager Context initScript）
     * 合并：原 toString/console 伪装 + PlaywrightUtil.initStealth 指纹补丁
     * ----------------------------------------------------- */

    /* 1. 保存原生 Function.prototype.toString */
    const nativeFunctionToString = Function.prototype.toString;

    /* 2. WeakMap：函数 → 伪原生源码 */
    const nativeSourceMap = new WeakMap();

    /* 3. 注册伪原生源码 */
    const registerNativeSource = (fn, source) => {
      try {
        nativeSourceMap.set(fn, source);
      } catch (_) {}
    };

    /* 4. 劫持 Function.prototype.toString */
    Object.defineProperty(Function.prototype, "toString", {
      configurable: true,
      writable: true,
      value: function toString() {
        if (nativeSourceMap.has(this)) {
          return nativeSourceMap.get(this);
        }
        return nativeFunctionToString.call(this);
      },
    });

    /* 5. 伪装 Function.prototype.toString 自身 */
    registerNativeSource(
      Function.prototype.toString,
      nativeFunctionToString.toString(),
    );

    /* 6. stealthify：包装函数但保持“原生外观” */
    const stealthify = (obj, prop, handler) => {
      const original = obj[prop];
      if (typeof original !== "function") return;

      const wrapped = function (...args) {
        return handler.call(this, original, args);
      };
      const namePropertyDescriptor = Object.getOwnPropertyDescriptor(
        wrapped,
        "name",
      );
      Object.defineProperty(wrapped, "name", {
        ...namePropertyDescriptor,
        value: prop,
      });
      try {
        Object.setPrototypeOf(wrapped, Object.getPrototypeOf(original));
      } catch (_) {}

      registerNativeSource(wrapped, nativeFunctionToString.call(original));

      const desc = Object.getOwnPropertyDescriptor(obj, prop);
      Object.defineProperty(obj, prop, {
        ...desc,
        value: wrapped,
      });
    };

    /* 7. console 降噪：避免 DevTools/CDP 展开敏感对象 */
    const filterConsoleArgs = (args) =>
      args.map((arg) => {
        if (arg && typeof arg === "object") {
          return {};
        }
        return arg;
      });

    ["log", "debug", "info", "warn", "error", "dir", "table"].forEach((name) => {
      stealthify(console, name, (original, args) => {
        return original.apply(console, filterConsoleArgs(args));
      });
    });

    /* 8. 核心自动化指纹：webdriver / cdc_* / chrome / languages / plugins */
    try {
      Object.defineProperty(navigator, "webdriver", {
        get: () => undefined,
        configurable: true,
      });
    } catch (_) {}

    try {
      delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array;
      delete window.cdc_adoQpoasnfa76pfcZLmcfl_JSON;
      delete window.cdc_adoQpoasnfa76pfcZLmcfl_Object;
      delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise;
      delete window.cdc_adoQpoasnfa76pfcZLmcfl_Proxy;
      delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol;
      delete window.cdc_adoQpoasnfa76pfcZLmcfl_Window;
    } catch (_) {}

    try {
      window.chrome = window.chrome || { runtime: {}, app: { isInstalled: false }, csi: function(){}, loadTimes: function(){} };
      try { if (!window.navigator.chrome) { Object.defineProperty(navigator, 'chrome', { get: () => window.chrome, configurable: true }); } } catch (_) {}
    } catch (_) {}

    try {
      Object.defineProperty(navigator, "languages", {
        get: () => ["zh-CN", "zh"],
        configurable: true,
      });
    } catch (_) {}

    try {
      Object.defineProperty(navigator, "plugins", {
        get: () => [1, 2, 3, 4, 5],
        configurable: true,
      });
    } catch (_) {}

    /* 9. 防御：registerNativeSource 自身伪原生 */
    registerNativeSource(
      registerNativeSource,
      "function registerNativeSource() { [native code] }",
    );
  })();
