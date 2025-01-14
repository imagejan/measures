/**
 * Code taken from:
 * https://github.com/CellTrackingChallenge/label-fusion-ng/blob/master/src/main/java/de/mpicbg/ulman/fusion/util/loggers/SimpleConsoleLogger.java
 */
package net.celltrackingchallenge.measures.util;

import org.scijava.Context;
import org.scijava.log.LogSource;
import org.scijava.log.LogMessage;
import org.scijava.log.LogListener;
import org.scijava.log.Logger;
import org.scijava.log.LogService;
import org.scijava.plugin.PluginInfo;

public class SimpleConsoleLogger implements LogService
{
	final String prefix;

	public SimpleConsoleLogger() {
		prefix = "";
	}

	public SimpleConsoleLogger(final String prefix) {
		this.prefix = prefix;
	}

	@Override
	public void setLevel(int level) {
	}

	@Override
	public void setLevel(String classOrPackageName, int level) {
	}

	@Override
	public void setLevelForLogger(String source, int level) {
	}

	@Override
	public void alwaysLog(int level, Object msg, Throwable t) {
	}

	@Override
	public LogSource getSource() {
		return null;
	}

	@Override
	public int getLevel() {
		return 0;
	}

	@Override
	public Logger subLogger(String name, int level) {
		return new SimpleConsoleLogger(name);
	}

	@Override
	public void addLogListener(LogListener listener) {
	}

	@Override
	public void removeLogListener(LogListener listener) {
	}

	@Override
	public void notifyListeners(LogMessage message) {
	}

	@Override
	public Context context() {
		return null;
	}

	@Override
	public Context getContext() {
		return null;
	}

	@Override
	public double getPriority() {
		return 0;
	}

	@Override
	public void setPriority(double priority) {
	}

	@Override
	public PluginInfo<?> getInfo() {
		return null;
	}

	@Override
	public void setInfo(PluginInfo<?> info) {
	}

	@Override
	public void debug(Object msg) {
		System.out.println( createMessage("DBG", msg) );
	}

	@Override
	public void error(Object msg) {
		System.out.println( createMessage("ERROR", msg) );
	}

	@Override
	public void info(Object msg) {
		System.out.println( createMessage("INFO", msg) );
	}

	@Override
	public void trace(Object msg) {
		System.out.println( createMessage("TRACE", msg) );
	}

	@Override
	public void warn(Object msg) {
		System.out.println( createMessage("WARN", msg) );
	}

	String createMessage(final String reportedLevel, final Object message) {
		return prefix + "[" + reportedLevel + "] " + message;
	}
}
