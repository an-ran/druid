package com.alibaba.druid.support.http;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.JMException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.alibaba.druid.VERSION;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.DruidDataSourceStatManager;
import com.alibaba.druid.stat.JdbcSqlStat;
import com.alibaba.druid.stat.JdbcStatManager;
import com.alibaba.druid.util.IOUtils;
import com.alibaba.druid.util.StringUtils;
import com.alibaba.fastjson.JSON;

/**
 * @author sandzhang<sandzhangtoo@gmail.com>
 */
public class StatViewServlet extends HttpServlet {

    /**
     * 
     */
    private static final long   serialVersionUID            = 1L;

    private final static int    RESULT_CODE_SUCCESS         = 1;
    private final static int    RESULT_CODE_ERROR           = -1;

    private final static String RESOURCE_PATH               = "support/http/resources";
    private final static String TEMPLATE_PAGE_RESOURCE_PATH = RESOURCE_PATH + "/template.html";

    public String               templatePage;

    public void init() throws ServletException {
        try {
            templatePage = IOUtils.readFromResource(TEMPLATE_PAGE_RESOURCE_PATH);
        } catch (IOException e) {
            throw new ServletException("error read templatePage:" + TEMPLATE_PAGE_RESOURCE_PATH, e);
        }
    }

    public void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String contextPath = request.getContextPath();
        String servletPath = request.getServletPath();
        String requestURI = request.getRequestURI();

        response.setCharacterEncoding("utf-8");

        if (contextPath == null) { // root context
            contextPath = "";
        }

        String path = requestURI.substring(contextPath.length() + servletPath.length());

        if (path.length() == 0) {
            returnResourceFile("/index.html", response);
            return;
        }

        if (path.equals("/basic.json")) {
            returnJSONBasicStat(request, response);
            return;
        }

        if (path.equals("/reset-all.json")) {
            resetAllStat();
            returnJSONResult(request, response, RESULT_CODE_SUCCESS, null);
            return;
        }

        if (path.equals("/datasource.json")) {
            returnJSONResult(request, response, RESULT_CODE_SUCCESS, getDataSourceStatList());
            return;
        }

        if (path.startsWith("/datasource-")) {
            Integer id = StringUtils.subStringToInteger(path, "datasource-", ".");
            Object result = getDataSourceStatData(id);
            returnJSONResult(request, response, result == null ? RESULT_CODE_ERROR : RESULT_CODE_SUCCESS, result);
            return;
        }

        if (path.equals("/sql.json")) {
            returnJSONResult(request, response, RESULT_CODE_SUCCESS, getSqlStatDataList());
            return;
        }

        if (path.startsWith("/sql-")) {
            Integer id = StringUtils.subStringToInteger(path, "sql-", ".");
            if (path.endsWith(".json")) {
                Object result = getSqlStatData(id);
                returnJSONResult(request, response, result == null ? RESULT_CODE_ERROR : RESULT_CODE_SUCCESS, result);
                return;
            }

            if (path.endsWith(".html")) {
                JdbcSqlStat sqlStat = getSqlStatById(id);
                returnViewSqlStat(sqlStat, response);
                return;
            }
            return;
        }

        // find file in resources path
        returnResourceFile(path, response);
    }

    private void returnViewSqlStat(JdbcSqlStat sqlStat, HttpServletResponse response) throws IOException {
        if (sqlStat == null) return;

        StringBuilder content = new StringBuilder();

        content.append("<h2>FULL SQL</h2> <h4>" + sqlStat.getSql() + "</h4>");
        content.append("<h2>Format View:</h2>");
        content.append("<textarea style='width:99%;height:120px;;border:1px #A8C7CE solid;line-height:20px;font-size:12px;'>");
        content.append(SQLUtils.format(sqlStat.getSql(), sqlStat.getDbType()));
        content.append("</textarea><br />");
        content.append("<p>API:com.alibaba.druid.sql.SQLUtils.format(sql,DBType);</p>");
        content.append("<br />");

        List<SQLStatement> statementList = SQLUtils.parseStatements(sqlStat.getSql(), sqlStat.getDbType());
        if (!statementList.isEmpty()) {
            content.append("<h2>Parse View:</h2>");

            SQLStatement statemen = statementList.get(0);
            SchemaStatVisitor visitor = SQLUtils.createSchemaStatVisitor(statementList, sqlStat.getDbType());
            statemen.accept(visitor);
            content.append("<table cellpadding='5' cellspacing='1' width='99%'>");
            content.append("<tr>");
            content.append("<td class='td_lable' width='130'>Tables</td>");
            content.append("<td>" + visitor.getTables() + "</td>");
            content.append("</tr>");

            content.append("<tr>");
            content.append("<td class='td_lable'>Fields</td>");
            content.append("<td>" + visitor.getColumns() + "</td>");
            content.append("</tr>");
            content.append("<tr>");
            content.append("<td class='td_lable'>Coditions</td>");
            content.append("<td>" + visitor.getConditions() + "</td>");
            content.append("</tr>");

            content.append("<tr>");
            content.append("<td class='td_lable'>Relationships</td>");
            content.append("<td>" + visitor.getRelationships() + "</td>");
            content.append("</tr>");

            content.append("<tr>");
            content.append("<td class='td_lable'>OrderByColumns</td>");
            content.append("<td>" + visitor.getOrderByColumns() + "</td>");
            content.append("</tr>");

            content.append("</table>");

            content.append("<br />");
            content.append("<p>API:</p>");
            content.append("<p>");
            content.append("List<SQLStatement> statementList = SQLUtils.parseStatements(sqlStat.getSql(), sqlStat.getDbType())<br />");
            content.append("SQLStatement statemen = statementList.get(0);</br>");
            content.append("SchemaStatVisitor visitor = SQLUtils.createSchemaStatVisitor(statementList, sqlStat.getDbType());<br />");
            content.append("statemen.accept(visitor);<br />");
            content.append("visitor.getTables() / visitor.getColumns() / visitor.getOrderByColumns() / visitor.getConditions() / visitor.getRelationships()<br />");
            content.append("</p>");
            content.append("<br />");
        }

        response.getWriter().print(mergeTemplatePage("Druid Sql View", content.toString()));

    }

    private void resetAllStat() {
        JdbcStatManager.getInstance().reset();
        DruidDataSourceStatManager.getInstance().reset();
    }

    private List<String> getDriversData() {
        List<String> drivers = new ArrayList<String>();
        for (Enumeration<Driver> e = DriverManager.getDrivers(); e.hasMoreElements();) {
            Driver driver = e.nextElement();
            drivers.add(driver.getClass().getName());
        }
        return drivers;
    }

    private void returnJSONBasicStat(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Map<String, Object> dataMap = new LinkedHashMap<String, Object>();
        dataMap.put("Version", VERSION.getVersionNumber());
        dataMap.put("Drivers", getDriversData());

        returnJSONResult(request, response, RESULT_CODE_SUCCESS, dataMap);

    }

    private List<Object> getDataSourceStatList() {
        List<Object> datasourceList = new ArrayList<Object>();
        for (DruidDataSource dataSource : DruidDataSourceStatManager.getDruidDataSourceInstances()) {
            datasourceList.add(dataSourceToMapData(dataSource));
        }
        return datasourceList;
    }

    private Map<String, Object> getDataSourceStatData(Integer id) {
        if (id == null) {
            return null;
        }
        DruidDataSource datasource = getDruidDataSourceById(id);
        return datasource == null ? null : dataSourceToMapData(datasource);
    }

    private Map<String, Object> getSqlStatData(Integer id) {
        if (id == null) {
            return null;
        }
        JdbcSqlStat sqlStat = getSqlStatById(id);
        return sqlStat == null ? null : getSqlStatData(sqlStat);
    }

    private JdbcSqlStat getSqlStatById(Integer id) {
        for (DruidDataSource ds : DruidDataSourceStatManager.getDruidDataSourceInstances()) {
            JdbcSqlStat sqlStat = ds.getDataSourceStat().getSqlStat(id);
            if (sqlStat != null) return sqlStat;
        }
        return null;
    }

    private DruidDataSource getDruidDataSourceById(Integer identity) {
        if (identity == null) {
            return null;
        }
        for (DruidDataSource datasource : DruidDataSourceStatManager.getDruidDataSourceInstances()) {
            if (System.identityHashCode(datasource) == identity) {
                return datasource;
            }
        }
        return null;
    }

    private Map<String, Object> getSqlStatData(JdbcSqlStat sqlStat) {
        try {
            return sqlStat.getData();
        } catch (JMException e) {
        }
        return null;
    }

    private List<Object> getSqlStatDataList() {
        List<Object> array = new ArrayList<Object>();
        for (DruidDataSource datasource : DruidDataSourceStatManager.getDruidDataSourceInstances()) {
            for (JdbcSqlStat sqlStat : datasource.getDataSourceStat().getSqlStatMap().values()) {
                array.add(getSqlStatData(sqlStat));
            }
        }
        return array;
    }

    private Map<String, Object> dataSourceToMapData(DruidDataSource dataSource) {

        Map<String, Object> dataMap = new LinkedHashMap<String, Object>();
        dataMap.put("Identity", System.identityHashCode(dataSource));
        dataMap.put("Name", dataSource.getName());
        dataMap.put("DbType", dataSource.getDbType());
        dataMap.put("DriverClassName", dataSource.getDriverClassName());

        dataMap.put("URL", dataSource.getUrl());
        dataMap.put("UserName", dataSource.getUsername());
        dataMap.put("FilterClassNames", dataSource.getFilterClassNames());

        dataMap.put("WaitThreadCount", dataSource.getWaitThreadCount());
        dataMap.put("NotEmptyWaitCount", dataSource.getNotEmptyWaitCount());
        dataMap.put("NotEmptyWaitMillis", dataSource.getNotEmptyWaitMillis());

        dataMap.put("PoolingCount", dataSource.getPoolingCount());
        dataMap.put("PoolingPeak", dataSource.getPoolingPeak());
        dataMap.put("PoolingPeakTime",
                    dataSource.getPoolingPeakTime() == null ? null : dataSource.getPoolingPeakTime().toString());

        dataMap.put("ActiveCount", dataSource.getActiveCount());
        dataMap.put("ActivePeak", dataSource.getActivePeak());
        dataMap.put("ActivePeakTime",
                    dataSource.getActivePeakTime() == null ? null : dataSource.getActivePeakTime().toString());

        dataMap.put("InitialSize", dataSource.getInitialSize());
        dataMap.put("MinIdle", dataSource.getMinIdle());
        dataMap.put("MaxActive", dataSource.getMaxActive());

        dataMap.put("TestOnBorrow", dataSource.isTestOnBorrow());
        dataMap.put("TestWhileIdle", dataSource.isTestWhileIdle());

        dataMap.put("LogicConnectCount", dataSource.getConnectCount());
        dataMap.put("LogicCloseCount", dataSource.getCloseCount());
        dataMap.put("LogicConnectErrorCount", dataSource.getConnectErrorCount());

        dataMap.put("PhysicalConnectCount", dataSource.getCreateCount());
        dataMap.put("PhysicalCloseCount", dataSource.getDestroyCount());
        dataMap.put("PhysicalConnectErrorCount", dataSource.getCreateErrorCount());

        dataMap.put("PSCacheAccessCount", dataSource.getCachedPreparedStatementAccessCount());
        dataMap.put("PSCacheHitCount", dataSource.getCachedPreparedStatementHitCount());
        dataMap.put("PSCacheMissCount", dataSource.getCachedPreparedStatementMissCount());

        dataMap.put("StartTransactionCount", dataSource.getStartTransactionCount());
        dataMap.put("TransactionHistogramValues", dataSource.getTransactionHistogramValues());
        return dataMap;
    }

    private void returnJSONResult(HttpServletRequest request, HttpServletResponse response, int resultCode,
                                  Object content) throws IOException {
        PrintWriter out = response.getWriter();

        Map<String, Object> dataMap = new LinkedHashMap<String, Object>();
        dataMap.put("ResultCode", resultCode);
        dataMap.put("Content", content);

        out.print(JSON.toJSONString(dataMap));
    }

    private void returnResourceFile(String fileName, HttpServletResponse response) throws ServletException, IOException {
        String text = IOUtils.readFromResource(RESOURCE_PATH + fileName);
        if (fileName.endsWith(".css")) {
            response.setContentType("text/css;charset=utf-8");
        } else if (fileName.endsWith(".js")) {
            response.setContentType("text/javascript;charset=utf-8");
        }
        response.getWriter().write(text);
    }

    private String mergeTemplatePage(String title, String content) {
        return templatePage.replaceAll("\\{title\\}", title).replaceAll("\\{content\\}", content);
    }
}
