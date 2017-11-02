package net.jplugin.core.mtenant.handler2;

import java.sql.Connection;
import java.sql.SQLException;

import net.jplugin.common.kits.StringKit;
import net.jplugin.core.config.api.ConfigFactory;
import net.jplugin.core.das.api.sqlrefactor.ISqlRefactor;
import net.jplugin.core.das.route.impl.conn.RouterConnection;
import net.jplugin.core.kernel.api.ctx.ThreadLocalContextManager;
import net.jplugin.core.log.api.LogFactory;
import net.jplugin.core.log.api.Logger;
import net.jplugin.core.mtenant.api.ITenantStoreStrategyProvidor.Mode;
import net.jplugin.core.mtenant.api.ITenantStoreStrategyProvidor.Strategy;
import net.sf.jsqlparser.JSQLParserException;

public class SqlMultiTenantHanlderMixedImpl implements ISqlRefactor {
	/**
	 * @param dataSourceName
	 * @param sql
	 * @return
	 */
	private static Logger logger = LogFactory.getLogger(SqlMultiTenantHanlderMixedImpl.class);
	private boolean allDataSource;
	private String[] dataSources = null;
	
	boolean init;
	public void init(){
		if (!init){
			init = true;

			String datasource = ConfigFactory.getStringConfig("mtenant.datasource", "ALL");
			if ("ALL".equals(datasource)){
				allDataSource = true;
			}else{
				allDataSource = false;	
				dataSources = StringKit.splitStr(datasource, ",");
			}
		}
	}
	


//	@Override
	public  String refactSql(String dataSourceName, String sql,Connection conn) {
		init();
		String result = handleInner(dataSourceName, sql,conn);
		if (logger.isDebugEnabled()){
			if (!sql.equals(result)){
				logger.debug("BeforeSQL = "+sql);
				logger.debug("After SQL = "+result);
			}
		}
		return result;
	}
	public  String handleInner(String dataSourceName, String sql,Connection conn) {
		if (!this.allDataSource && !inDataSourceList(dataSourceName))
			return sql;
		
		//router connection数据源不能配置为多租户
		boolean isRouter =false;
		try{
			isRouter = conn.isWrapperFor(RouterConnection.class);
		}catch(Exception e){
			throw new RuntimeException("Error while call isWrapper",e);
		}
		if (isRouter) 
			throw new RuntimeException("Router connection can't be configed with multinant."+conn.getClass().getName());
		
		String tid = ThreadLocalContextManager.getRequestInfo().getCurrentTenantId();
		
		//在列表中，必须能够处理
		String schemaPrefix = ConfigFactory.getStringConfig("mtenant.schema-prefix."+dataSourceName);
		if (StringKit.isNull(schemaPrefix)){
			throw new RuntimeException("The multi tenant datasource ["+dataSourceName+"] must be configed with a [schema-prefix."+dataSourceName+"] key");
		}
		
		if (StringKit.isNull(tid)){
			throw new RuntimeException("The multi tenant datasource ["+dataSourceName+"] must be called with a tenantid request attribute");
		}
		
		//重写sql
		return handle(sql, dataSourceName, tid);
	}

	
	private boolean inDataSourceList(String dataSourceName) {
		for (String s:this.dataSources){
			if (s.equals(dataSourceName)) return true;
		}
		return false;
	}
	

	private static String handle(String sql, String originalDataSource,String tid) {
		Strategy stg = getStrategy(sql, originalDataSource);
		
		SqlHandlerVisitorForMixed v;
		if (stg.getMode()==Mode.SHARE){
			v = new SqlHandlerVisitorForMixed(stg.getFinalSchema(), tid);
		}else{
			v = new SqlHandlerVisitorForMixed(stg.getFinalSchema());
		}
		
		try {
			return v.handle(sql);
		} catch (JSQLParserException e) {
			throw new RuntimeException("SQL gremma error."+e.getMessage() +"  "+sql,e);
		}
	}

	private static Strategy getStrategy(String sql, String dataSource) {
		String tid = ThreadLocalContextManager.getRequestInfo().getCurrentTenantId();
		if (StringKit.isNull(tid))
			throw new RuntimeException("Can't find tenantid when handle sql");
		
		Strategy stragegy=null;
		if (TenantStoreStrategyManager.instance.isProviderExist()){
			stragegy = TenantStoreStrategyManager.instance.getStragegy(tid);
			if (stragegy==null)
				throw new RuntimeException("Can't get tenant store stragegy for tenent:"+tid);
		}else{
			stragegy = getDefaultStrategy(sql,dataSource,tid);
		}
		return stragegy;
	}

	private static Strategy getDefaultStrategy(String sql, String dataSource, String tid) {
		Strategy s = new Strategy();
		s.setMode(Mode.ONESELF);
		s.setFinalSchema(dataSource+"_"+tid);
		return s;
	}

}
