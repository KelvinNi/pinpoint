package com.navercorp.pinpoint.profiler.modifier.arcus;

import java.security.ProtectionDomain;
import java.util.List;

import com.navercorp.pinpoint.bootstrap.Agent;
import com.navercorp.pinpoint.bootstrap.instrument.ByteCodeInstrumentor;
import com.navercorp.pinpoint.bootstrap.instrument.InstrumentClass;
import com.navercorp.pinpoint.bootstrap.instrument.MethodInfo;
import com.navercorp.pinpoint.bootstrap.instrument.Type;
import com.navercorp.pinpoint.bootstrap.interceptor.Interceptor;
import com.navercorp.pinpoint.bootstrap.interceptor.ParameterExtractorSupport;
import com.navercorp.pinpoint.bootstrap.interceptor.SimpleAroundInterceptor;
import com.navercorp.pinpoint.profiler.interceptor.bci.*;
import com.navercorp.pinpoint.profiler.modifier.AbstractModifier;
import com.navercorp.pinpoint.profiler.modifier.arcus.interceptor.ArcusScope;
import com.navercorp.pinpoint.profiler.modifier.arcus.interceptor.IndexParameterExtractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author netspider
 * @author emeroad
 */
public class MemcachedClientModifier extends AbstractModifier {

	private final Logger logger = LoggerFactory.getLogger(MemcachedClientModifier.class.getName());

	public MemcachedClientModifier(ByteCodeInstrumentor byteCodeInstrumentor, Agent agent) {
		super(byteCodeInstrumentor, agent);
	}

	public String getTargetClass() {
		return "net/spy/memcached/MemcachedClient";
	}

	public byte[] modify(ClassLoader classLoader, String javassistClassName,
			ProtectionDomain protectedDomain, byte[] classFileBuffer) {
		if (logger.isInfoEnabled()) {
			logger.info("Modifing. {}", javassistClassName);
		}

		try {
			InstrumentClass aClass = byteCodeInstrumentor.getClass(classLoader, javassistClassName, classFileBuffer);

            String[] args = {"java.lang.String", "net.spy.memcached.ops.Operation"};
            if (!checkCompatibility(aClass, args)) {
                return null;
            }
			aClass.addTraceVariable("__serviceCode", "__setServiceCode", "__getServiceCode", "java.lang.String");

			Interceptor addOpInterceptor = byteCodeInstrumentor.newInterceptor(classLoader, protectedDomain,
                    "com.navercorp.pinpoint.profiler.modifier.arcus.interceptor.AddOpInterceptor");
            aClass.addInterceptor("addOp", args, addOpInterceptor, Type.before);

			// 모든 public 메소드에 ApiInterceptor를 적용한다.
            final List<MethodInfo> declaredMethods = aClass.getDeclaredMethods(new MemcachedMethodFilter());

            for (MethodInfo method : declaredMethods) {
                SimpleAroundInterceptor apiInterceptor = (SimpleAroundInterceptor) byteCodeInstrumentor.newInterceptor(classLoader, protectedDomain, "com.navercorp.pinpoint.profiler.modifier.arcus.interceptor.ApiInterceptor");
                if (agent.getProfilerConfig().isMemcachedKeyTrace()) {
                    final int index = ParameterUtils.findFirstString(method, 3);
                    if (index != -1) {
                        ((ParameterExtractorSupport)apiInterceptor).setParameterExtractor(new IndexParameterExtractor(index));
                    }
                }
				aClass.addScopeInterceptor(method.getName(), method.getParameterTypes(), apiInterceptor, ArcusScope.SCOPE);
			}
			return aClass.toBytecode();
		} catch (Exception e) {
			if (logger.isWarnEnabled()) {
				logger.warn(e.getMessage(), e);
			}
			return null;
		}
	}

    private boolean checkCompatibility(InstrumentClass aClass, String[] args) {
        // 일단 addOp 존재유무로 체크
        final boolean addOp = aClass.hasDeclaredMethod("addOp", args);
        if (!addOp) {
            logger.warn("addOp() not found. skip MemcachedClientModifier");
        }
        return addOp;
    }

}