import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.security.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class SmartPayrollServer {
    private static final int PORT = 8080;
    private static final String DB_URL = "jdbc:mysql://localhost:3306/payroll_db?useSSL=false&serverTimezone=UTC";
    private static final String DB_USER = "root";
    // IMPORTANT: replace this with your own MySQL password.
    private static final String DB_PASSWORD = System.getenv().getOrDefault("SMARTPAYROLL_DB_PASSWORD", "");
    private static Connection db() throws SQLException { return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD); }
    private static final Map<String, UserSession> SESSIONS = new ConcurrentHashMap<>();
    private static class UserSession { final String role, employeeId, username; UserSession(String r,String e,String u){role=r;employeeId=e;username=u;} }
    private static UserSession user(HttpExchange x) {
        String cookie=x.getRequestHeaders().getFirst("Cookie");
        if(cookie==null)return null;
        for(String part:cookie.split(";")){String[] a=part.trim().split("=",2);if(a.length==2&&a[0].equals("SPSESSION"))return SESSIONS.get(a[1]);}
        return null;
    }
    private static UserSession require(HttpExchange x, String role) throws IOException {
        UserSession u=user(x);
        if(u==null){send(x,401,"{\"error\":\"Please log in first.\"}","application/json; charset=utf-8");return null;}
        if(role!=null&&!role.equals(u.role)){send(x,403,"{\"error\":\"You do not have permission for this action.\"}","application/json; charset=utf-8");return null;}
        return u;
    }
    private static String hashPassword(String password, byte[] salt) throws Exception {
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,120000,256);
        byte[] bytes=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        return Base64.getEncoder().encodeToString(bytes);
    }
    private static boolean verifyPassword(String password,String saltText,String hash) throws Exception {
        byte[] salt=Base64.getDecoder().decode(saltText);
        return MessageDigest.isEqual(Base64.getDecoder().decode(hash),Base64.getDecoder().decode(hashPassword(password,salt)));
    }
    private static void ensureAuthTables() throws Exception {
        try(Connection c=db();Statement s=c.createStatement()){
            s.executeUpdate("CREATE TABLE IF NOT EXISTS user_account (username VARCHAR(100) PRIMARY KEY, role VARCHAR(20) NOT NULL, employee_id VARCHAR(20) NULL UNIQUE, salt VARCHAR(100) NOT NULL, password_hash VARCHAR(200) NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY (employee_id) REFERENCES employee(employee_id) ON DELETE CASCADE)");
            try(PreparedStatement p=c.prepareStatement("SELECT username FROM user_account WHERE username='admin'");ResultSet r=p.executeQuery()){
                if(!r.next()){byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);try(PreparedStatement ins=c.prepareStatement("INSERT INTO user_account(username,role,employee_id,salt,password_hash) VALUES('admin','ADMIN',NULL,?,?)")){ins.setString(1,Base64.getEncoder().encodeToString(salt));ins.setString(2,hashPassword("Admin@12345",salt));ins.executeUpdate();}}
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        ensureAuthTables();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/login", SmartPayrollServer::login);
        server.createContext("/api/session", SmartPayrollServer::session);
        server.createContext("/api/logout", SmartPayrollServer::logout);
        server.createContext("/api/me", SmartPayrollServer::me);
        server.createContext("/api/change-password", SmartPayrollServer::changePassword);
        server.createContext("/api/employees", SmartPayrollServer::employees);
        server.createContext("/api/payroll", SmartPayrollServer::payroll);
        server.createContext("/", SmartPayrollServer::staticFiles);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("SmartPayroll is running at http://localhost:" + PORT);
        System.out.println("Keep this terminal open while using the website.");
    }

    private static void login(HttpExchange x) throws IOException {
        try {
            if(!"POST".equalsIgnoreCase(x.getRequestMethod())){send(x,405,"{\"error\":\"Method not allowed\"}","application/json; charset=utf-8");return;}
            Map<String,String> f=form(x);String username=req(f,"username"),password=req(f,"password");
            try(Connection c=db();PreparedStatement p=c.prepareStatement("SELECT role,employee_id,salt,password_hash FROM user_account WHERE username=?")){
                p.setString(1,username);
                try(ResultSet r=p.executeQuery()){
                    if(!r.next()||!verifyPassword(password,r.getString(3),r.getString(4))){send(x,401,"{\"error\":\"Incorrect username or password.\"}","application/json; charset=utf-8");return;}
                    String token=UUID.randomUUID().toString().replace("-","");
                    UserSession u=new UserSession(r.getString(1),r.getString(2),username);SESSIONS.put(token,u);
                    x.getResponseHeaders().add("Set-Cookie","SPSESSION="+token+"; Path=/; HttpOnly; SameSite=Strict");
                    send(x,200,"{\"role\":"+q(u.role)+",\"username\":"+q(u.username)+",\"employeeId\":"+q(u.employeeId)+"}","application/json; charset=utf-8");
                }
            }
        }catch(Exception e){send(x,400,"{\"error\":"+q(safe(e.getMessage()))+"}","application/json; charset=utf-8");}
    }
    private static void session(HttpExchange x) throws IOException {
        UserSession u=user(x);
        if(u==null){send(x,200,"{\"loggedIn\":false}","application/json; charset=utf-8");return;}
        send(x,200,"{\"loggedIn\":true,\"role\":"+q(u.role)+",\"username\":"+q(u.username)+",\"employeeId\":"+q(u.employeeId)+"}","application/json; charset=utf-8");
    }
    private static void logout(HttpExchange x) throws IOException {
        String cookie=x.getRequestHeaders().getFirst("Cookie");
        if(cookie!=null)for(String part:cookie.split(";")){String[] a=part.trim().split("=",2);if(a.length==2&&a[0].equals("SPSESSION"))SESSIONS.remove(a[1]);}
        x.getResponseHeaders().add("Set-Cookie","SPSESSION=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
        send(x,200,"{\"message\":\"Logged out\"}","application/json; charset=utf-8");
    }
    private static void me(HttpExchange x) throws IOException {
        UserSession u=require(x,"EMPLOYEE");if(u==null)return;
        try(Connection c=db();PreparedStatement p=c.prepareStatement("SELECT employee_id,full_name,designation,department,employment_type,basic_salary,email FROM employee WHERE employee_id=?")){
            p.setString(1,u.employeeId);try(ResultSet r=p.executeQuery()){
                if(!r.next()){send(x,404,"{\"error\":\"Employee record not found.\"}","application/json; charset=utf-8");return;}
                send(x,200,"{\"id\":"+q(r.getString(1))+",\"name\":"+q(r.getString(2))+",\"designation\":"+q(r.getString(3))+",\"department\":"+q(r.getString(4))+",\"type\":"+q(r.getString(5))+",\"basic\":"+r.getDouble(6)+",\"email\":"+q(r.getString(7))+"}","application/json; charset=utf-8");
            }
        }catch(Exception e){send(x,500,"{\"error\":"+q(safe(e.getMessage()))+"}","application/json; charset=utf-8");}
    }


    private static void changePassword(HttpExchange x) throws IOException {
        UserSession u=require(x,"EMPLOYEE"); if(u==null)return;
        if(!"POST".equalsIgnoreCase(x.getRequestMethod())){send(x,405,"{\"error\":\"Method not allowed\"}","application/json; charset=utf-8");return;}
        try {
            Map<String,String> f=form(x);
            String current=req(f,"currentPassword"), next=req(f,"newPassword");
            if(next.length()<8) throw new IllegalArgumentException("New password must be at least 8 characters.");
            if(next.equals(current)) throw new IllegalArgumentException("New password must be different from your current password.");
            try(Connection c=db();PreparedStatement p=c.prepareStatement("SELECT salt,password_hash FROM user_account WHERE username=? AND role='EMPLOYEE'")) {
                p.setString(1,u.username);
                try(ResultSet r=p.executeQuery()) {
                    if(!r.next() || !verifyPassword(current,r.getString(1),r.getString(2))) {
                        send(x,401,"{\"error\":\"Current password is incorrect.\"}","application/json; charset=utf-8"); return;
                    }
                    byte[] salt=new byte[16]; new SecureRandom().nextBytes(salt);
                    try(PreparedStatement update=c.prepareStatement("UPDATE user_account SET salt=?,password_hash=? WHERE username=? AND role='EMPLOYEE'")) {
                        update.setString(1,Base64.getEncoder().encodeToString(salt));
                        update.setString(2,hashPassword(next,salt)); update.setString(3,u.username); update.executeUpdate();
                    }
                }
            }
            send(x,200,"{\"message\":\"Password changed successfully.\"}","application/json; charset=utf-8");
        } catch(IllegalArgumentException e) { send(x,400,"{\"error\":"+q(e.getMessage())+"}","application/json; charset=utf-8"); }
          catch(Exception e) { send(x,500,"{\"error\":"+q(safe(e.getMessage()))+"}","application/json; charset=utf-8"); }
    }

    private static void employees(HttpExchange x) throws IOException {
        UserSession session=require(x,"ADMIN"); if(session==null)return;
        try {
            if ("GET".equalsIgnoreCase(x.getRequestMethod())) {
                List<String> rows = new ArrayList<>();
                try (Connection c = db(); PreparedStatement p = c.prepareStatement("SELECT employee_id, full_name, designation, department, employment_type, basic_salary, email FROM employee ORDER BY employee_id"); ResultSet r = p.executeQuery()) {
                    while (r.next()) rows.add("{\"id\":"+q(r.getString(1))+",\"name\":"+q(r.getString(2))+",\"designation\":"+q(r.getString(3))+",\"department\":"+q(r.getString(4))+",\"type\":"+q(r.getString(5))+",\"basic\":"+r.getDouble(6)+",\"email\":"+q(r.getString(7))+"}");
                }
                send(x, 200, "["+String.join(",", rows)+"]", "application/json; charset=utf-8");
            } else if ("POST".equalsIgnoreCase(x.getRequestMethod())) {
                Map<String,String> f = form(x);
                String id=req(f,"id"), name=req(f,"name"), designation=req(f,"designation"), department=req(f,"department"), type=req(f,"type");
                double basic=Double.parseDouble(req(f,"basic")); String email=f.getOrDefault("email", "");
                if (basic < 0) throw new IllegalArgumentException("Basic salary cannot be negative.");
                String password=req(f,"password");
                if(password.length()<8) throw new IllegalArgumentException("Employee password must be at least 8 characters.");
                try (Connection c=db()) {
                    c.setAutoCommit(false);
                    try(PreparedStatement p=c.prepareStatement("INSERT INTO employee (employee_id, full_name, designation, department, employment_type, basic_salary, email) VALUES (?,?,?,?,?,?,?)")){
                        p.setString(1,id);p.setString(2,name);p.setString(3,designation);p.setString(4,department);p.setString(5,type);p.setDouble(6,basic);p.setString(7,email);p.executeUpdate();
                    }
                    byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);
                    try(PreparedStatement a=c.prepareStatement("INSERT INTO user_account(username,role,employee_id,salt,password_hash) VALUES(?,'EMPLOYEE',?,?,?)")){
                        a.setString(1,id);a.setString(2,id);a.setString(3,Base64.getEncoder().encodeToString(salt));a.setString(4,hashPassword(password,salt));a.executeUpdate();
                    }
                    c.commit();
                }
                send(x,201,"{\"message\":\"Employee added successfully\"}","application/json; charset=utf-8");
            } else send(x,405,"{\"error\":\"Method not allowed\"}","application/json; charset=utf-8");
        } catch (SQLIntegrityConstraintViolationException e) { send(x,409,"{\"error\":\"Employee ID already exists. Use a different ID.\"}","application/json; charset=utf-8"); }
          catch (Exception e) { send(x,400,"{\"error\":"+q(safe(e.getMessage()))+"}","application/json; charset=utf-8"); }
    }

    private static void payroll(HttpExchange x) throws IOException {
        UserSession session=user(x); if(session==null){require(x,null);return;}
        if("POST".equalsIgnoreCase(x.getRequestMethod())&&!session.role.equals("ADMIN")){require(x,"ADMIN");return;}
        try {
            if ("GET".equalsIgnoreCase(x.getRequestMethod())) {
                List<String> rows=new ArrayList<>();
                String sql="SELECT p.payroll_id,p.employee_id,e.full_name,p.payroll_month,p.basic_salary,p.hra,p.da,p.travel_allowance,p.pf,p.insurance,p.tax,p.gross_salary,p.net_salary FROM payroll p JOIN employee e ON e.employee_id=p.employee_id";
                if(session.role.equals("EMPLOYEE")) sql += " WHERE p.employee_id=?";
                sql += " ORDER BY p.payroll_month DESC,p.payroll_id DESC";
                try(Connection c=db();PreparedStatement p=c.prepareStatement(sql)){
                    if(session.role.equals("EMPLOYEE"))p.setString(1,session.employeeId);
                    try(ResultSet r=p.executeQuery()){
                    while(r.next()) rows.add("{\"key\":"+q(r.getString(1))+",\"id\":"+q(r.getString(2))+",\"name\":"+q(r.getString(3))+",\"month\":"+q(r.getString(4))+",\"basic\":"+r.getDouble(5)+",\"hra\":"+r.getDouble(6)+",\"da\":"+r.getDouble(7)+",\"travel\":"+r.getDouble(8)+",\"pf\":"+r.getDouble(9)+",\"insurance\":"+r.getDouble(10)+",\"tax\":"+r.getDouble(11)+",\"gross\":"+r.getDouble(12)+",\"net\":"+r.getDouble(13)+"}");
                    }
                }
                send(x,200,"["+String.join(",",rows)+"]","application/json; charset=utf-8");
            } else if ("POST".equalsIgnoreCase(x.getRequestMethod())) {
                Map<String,String> f=form(x); String id=req(f,"id"), month=req(f,"month");
                double basic=num(f,"basic"),hraPct=num(f,"hraPct"),daPct=num(f,"daPct"),travel=num(f,"travel"),pfPct=num(f,"pfPct"),insurance=num(f,"insurance"),taxPct=num(f,"taxPct");
                if(basic<0||travel<0||insurance<0||hraPct<0||daPct<0||pfPct<0||taxPct<0) throw new IllegalArgumentException("Salary values cannot be negative.");
                double hra=basic*hraPct/100, da=basic*daPct/100, gross=basic+hra+da+travel, pf=basic*pfPct/100, tax=gross*taxPct/100, net=gross-pf-insurance-tax;
                if(net<0) throw new IllegalArgumentException("Net salary is negative. Please check the entered values.");
                String sql="INSERT INTO payroll (employee_id,payroll_month,basic_salary,hra,da,travel_allowance,pf,insurance,tax,gross_salary,net_salary) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
                try(Connection c=db();PreparedStatement p=c.prepareStatement(sql)) { p.setString(1,id);p.setString(2,month+"-01");p.setDouble(3,basic);p.setDouble(4,hra);p.setDouble(5,da);p.setDouble(6,travel);p.setDouble(7,pf);p.setDouble(8,insurance);p.setDouble(9,tax);p.setDouble(10,gross);p.setDouble(11,net);p.executeUpdate(); }
                send(x,201,"{\"message\":\"Payroll saved successfully\",\"gross\":"+gross+",\"net\":"+net+"}","application/json; charset=utf-8");
            } else send(x,405,"{\"error\":\"Method not allowed\"}","application/json; charset=utf-8");
        } catch(SQLIntegrityConstraintViolationException e) { send(x,409,"{\"error\":\"Payroll may already exist for this employee and month.\"}","application/json; charset=utf-8"); }
          catch(Exception e) { send(x,400,"{\"error\":"+q(safe(e.getMessage()))+"}","application/json; charset=utf-8"); }
    }

    private static void staticFiles(HttpExchange x) throws IOException {
        String path=x.getRequestURI().getPath(); if(path.equals("/")) path="/index.html";
        Path root=Paths.get("public").toAbsolutePath().normalize(); Path file=root.resolve(path.substring(1)).normalize();
        if(!file.startsWith(root)||!Files.exists(file)||Files.isDirectory(file)){send(x,404,"Not found","text/plain; charset=utf-8");return;}
        String type=file.toString().endsWith(".html")?"text/html; charset=utf-8":file.toString().endsWith(".css")?"text/css; charset=utf-8":"application/javascript; charset=utf-8";
        byte[] bytes=Files.readAllBytes(file);x.getResponseHeaders().set("Content-Type",type);x.sendResponseHeaders(200,bytes.length);try(OutputStream o=x.getResponseBody()){o.write(bytes);}
    }
    private static Map<String,String> form(HttpExchange x)throws IOException{String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);Map<String,String> m=new HashMap<>();for(String part:body.split("&")){String[] a=part.split("=",2);m.put(URLDecoder.decode(a[0],StandardCharsets.UTF_8),a.length>1?URLDecoder.decode(a[1],StandardCharsets.UTF_8):"");}return m;}
    private static String req(Map<String,String> f,String k){String v=f.get(k);if(v==null||v.isBlank())throw new IllegalArgumentException("Missing required field: "+k);return v.trim();}
    private static double num(Map<String,String> f,String k){return Double.parseDouble(req(f,k));}
    private static String q(String s){if(s==null)return "null";return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";}
    private static String safe(String s){return s==null?"Something went wrong":s.replace("\"", "'");}
    private static void send(HttpExchange x,int code,String body,String type)throws IOException{byte[] b=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",type);x.getResponseHeaders().set("Access-Control-Allow-Origin","*");x.sendResponseHeaders(code,b.length);try(OutputStream o=x.getResponseBody()){o.write(b);}}
}
