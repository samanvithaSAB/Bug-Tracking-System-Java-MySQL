package com.bugtracker;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.List;

public class BugTrackingApp extends JFrame {
    private static final String DB_URL = System.getenv().getOrDefault(
            "BUGTRACKER_DB_URL", "jdbc:mysql://localhost:3306/bug_tracking_system?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
    private static final String DB_USER = System.getenv().getOrDefault("BUGTRACKER_DB_USER", "root");
    private static final String DB_PASSWORD = System.getenv().getOrDefault("BUGTRACKER_DB_PASSWORD", "root");

    private final User currentUser;
    private JTabbedPane tabs;
    private JTable bugsTable;
    private DefaultTableModel bugsModel;
    private JTextField searchField;
    private JComboBox<String> statusFilter, priorityFilter, sortBox;
    private JComboBox<Choice> projectFilter, developerFilter;
    private JTextField fromDateField, toDateField;
    private int currentPage = 0;
    private static final int PAGE_SIZE = 10;

    record User(int id, String username, String fullName, String role) {
        @Override public String toString() { return fullName + " (" + role + ")"; }
    }

    private BugTrackingApp(User user) {
        this.currentUser = user;
        setTitle("Bug Tracking System & Project Management - " + user.fullName());
        setSize(1180, 740);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        buildUI();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    private static void initializeDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection(
                DB_URL.substring(0, DB_URL.indexOf("/", DB_URL.indexOf("//") + 2)) + "/", DB_USER, DB_PASSWORD);
             Statement s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE IF NOT EXISTS bug_tracking_system");
        } catch (SQLException ignored) {
            // If the URL already points to the database, continue with schema creation below.
        }
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS users (
                  id INT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(50) UNIQUE NOT NULL,
                  password VARCHAR(100) NOT NULL, full_name VARCHAR(100) NOT NULL,
                  role ENUM('ADMIN','DEVELOPER','TESTER') NOT NULL, active BOOLEAN DEFAULT TRUE,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS projects (
                  id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(120) UNIQUE NOT NULL,
                  description TEXT, start_date DATE, end_date DATE,
                  status ENUM('PLANNED','ACTIVE','COMPLETED','ON_HOLD') DEFAULT 'ACTIVE',
                  created_by INT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  FOREIGN KEY(created_by) REFERENCES users(id))""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS bugs (
                  id INT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(180) NOT NULL, description TEXT NOT NULL,
                  severity ENUM('LOW','MEDIUM','HIGH','CRITICAL') DEFAULT 'MEDIUM',
                  priority ENUM('LOW','MEDIUM','HIGH','URGENT') DEFAULT 'MEDIUM',
                  status ENUM('OPEN','IN_PROGRESS','RESOLVED','CLOSED','REOPENED') DEFAULT 'OPEN',
                  project_id INT NOT NULL, reporter_id INT NOT NULL, developer_id INT NULL,
                  reported_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  resolved_at TIMESTAMP NULL,
                  FOREIGN KEY(project_id) REFERENCES projects(id),
                  FOREIGN KEY(reporter_id) REFERENCES users(id),
                  FOREIGN KEY(developer_id) REFERENCES users(id))""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS bug_comments (
                  id INT AUTO_INCREMENT PRIMARY KEY, bug_id INT NOT NULL, user_id INT NOT NULL,
                  comment_text TEXT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  FOREIGN KEY(bug_id) REFERENCES bugs(id) ON DELETE CASCADE,
                  FOREIGN KEY(user_id) REFERENCES users(id))""");
            s.executeUpdate("""
                CREATE TABLE IF NOT EXISTS activity_logs (
                  id INT AUTO_INCREMENT PRIMARY KEY, bug_id INT NULL, project_id INT NULL,
                  actor_id INT NOT NULL, action VARCHAR(80) NOT NULL, details TEXT,
                  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  FOREIGN KEY(bug_id) REFERENCES bugs(id) ON DELETE SET NULL,
                  FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE SET NULL,
                  FOREIGN KEY(actor_id) REFERENCES users(id))""");
        }
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement(
                "INSERT IGNORE INTO users(username,password,full_name,role) VALUES(?,?,?,?)")) {
            String[][] seed = {
                {"admin","admin123","System Administrator","ADMIN"},
                {"tester","tester123","Demo Tester","TESTER"},
                {"developer","dev123","Demo Developer","DEVELOPER"}
            };
            for (String[] row : seed) {
                for (int i=0;i<row.length;i++) p.setString(i+1,row[i]);
                p.addBatch();
            }
            p.executeBatch();
        }
    }

    private static User authenticate(String username, String password) throws SQLException {
        String sql = "SELECT id,username,full_name,role FROM users WHERE username=? AND password=? AND active=TRUE";
        try (Connection c=connect(); PreparedStatement p=c.prepareStatement(sql)) {
            p.setString(1,username); p.setString(2,password);
            try (ResultSet r=p.executeQuery()) {
                return r.next() ? new User(r.getInt("id"),r.getString("username"),r.getString("full_name"),r.getString("role")) : null;
            }
        }
    }

    private void buildUI() {
        setLayout(new BorderLayout());
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(10,14,10,14));
        JLabel heading = new JLabel("BUG TRACKING SYSTEM & PROJECT MANAGEMENT");
        heading.setFont(new Font("SansSerif",Font.BOLD,19));
        top.add(heading,BorderLayout.WEST);
        top.add(new JLabel("Logged in: " + currentUser.fullName() + "  |  Role: " + currentUser.role()),BorderLayout.EAST);
        add(top,BorderLayout.NORTH);

        tabs = new JTabbedPane();
        tabs.addTab("Dashboard", dashboardPanel());
        tabs.addTab("Bugs", bugsPanel());
        tabs.addTab("Projects", projectsPanel());
        tabs.addTab("Reports", reportsPanel());
        tabs.addTab("Activity Log", activityPanel());
        if ("ADMIN".equals(currentUser.role())) tabs.addTab("Users", usersPanel());
        add(tabs,BorderLayout.CENTER);
    }

    private JPanel dashboardPanel() {
        JPanel panel = new JPanel(new BorderLayout(10,10));
        panel.setBorder(BorderFactory.createEmptyBorder(18,18,18,18));
        JPanel cards = new JPanel(new GridLayout(2,3,12,12));
        String[] labels={"Total Bugs","Open Bugs","In Progress","Resolved","Projects","Developers"};
        String[] queries={
            "SELECT COUNT(*) FROM bugs",
            "SELECT COUNT(*) FROM bugs WHERE status IN ('OPEN','REOPENED')",
            "SELECT COUNT(*) FROM bugs WHERE status='IN_PROGRESS'",
            "SELECT COUNT(*) FROM bugs WHERE status IN ('RESOLVED','CLOSED')",
            "SELECT COUNT(*) FROM projects",
            "SELECT COUNT(*) FROM users WHERE role='DEVELOPER' AND active=TRUE"
        };
        for(int i=0;i<labels.length;i++){
            JPanel card=new JPanel(new BorderLayout());
            card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(190,200,215)),BorderFactory.createEmptyBorder(16,16,16,16)));
            JLabel value=new JLabel(String.valueOf(scalar(queries[i])),SwingConstants.CENTER);
            value.setFont(new Font("SansSerif",Font.BOLD,30));
            JLabel label=new JLabel(labels[i],SwingConstants.CENTER);
            label.setFont(new Font("SansSerif",Font.PLAIN,14));
            card.add(value,BorderLayout.CENTER); card.add(label,BorderLayout.SOUTH); cards.add(card);
        }
        panel.add(cards,BorderLayout.NORTH);
        JTextArea info=new JTextArea("Welcome to the Bug Tracking System.\n\n"
                + "Use the Bugs tab to report, assign, update, search and filter bugs.\n"
                + "Use Projects to manage project records. Reports contains aggregate SQL analytics.\n"
                + "Every important bug change is recorded in the Activity Log.\n\n"
                + "Access is controlled by the logged-in role: ADMIN, TESTER or DEVELOPER.");
        info.setEditable(false); info.setFont(new Font("SansSerif",Font.PLAIN,15));
        info.setBorder(BorderFactory.createEmptyBorder(20,8,8,8));
        panel.add(info,BorderLayout.CENTER);
        return panel;
    }

    private JPanel bugsPanel() {
        JPanel panel=new JPanel(new BorderLayout(8,8));
        JPanel controls=new JPanel(new GridLayout(2,1,3,3));
        JPanel filterRow1=new JPanel(new FlowLayout(FlowLayout.LEFT));
        JPanel filterRow2=new JPanel(new FlowLayout(FlowLayout.LEFT));
        searchField=new JTextField(15);
        statusFilter=new JComboBox<>(new String[]{"ALL","OPEN","IN_PROGRESS","RESOLVED","CLOSED","REOPENED"});
        priorityFilter=new JComboBox<>(new String[]{"ALL","LOW","MEDIUM","HIGH","URGENT"});
        sortBox=new JComboBox<>(new String[]{"Newest first","Oldest first","Priority (high first)","Status"});
        projectFilter=new JComboBox<>(); projectFilter.addItem(new Choice(0,"ALL PROJECTS"));
        for(Choice x:choices("SELECT id,name FROM projects ORDER BY name"))projectFilter.addItem(x);
        developerFilter=new JComboBox<>(); developerFilter.addItem(new Choice(0,"ALL DEVELOPERS"));
        for(Choice x:choices("SELECT id,full_name FROM users WHERE role='DEVELOPER' AND active=TRUE ORDER BY full_name"))developerFilter.addItem(x);
        fromDateField=new JTextField(10);toDateField=new JTextField(10);
        JButton search=new JButton("Search / Filter"), reset=new JButton("Reset");
        search.addActionListener(e->{currentPage=0;loadBugs();});
        reset.addActionListener(e->{searchField.setText("");statusFilter.setSelectedIndex(0);priorityFilter.setSelectedIndex(0);sortBox.setSelectedIndex(0);
            projectFilter.setSelectedIndex(0);developerFilter.setSelectedIndex(0);fromDateField.setText("");toDateField.setText("");currentPage=0;loadBugs();});
        filterRow1.add(new JLabel("Keyword:"));filterRow1.add(searchField);filterRow1.add(new JLabel("Status:"));filterRow1.add(statusFilter);
        filterRow1.add(new JLabel("Priority:"));filterRow1.add(priorityFilter);filterRow1.add(new JLabel("Project:"));filterRow1.add(projectFilter);
        filterRow2.add(new JLabel("Developer:"));filterRow2.add(developerFilter);filterRow2.add(new JLabel("From (YYYY-MM-DD):"));filterRow2.add(fromDateField);
        filterRow2.add(new JLabel("To:"));filterRow2.add(toDateField);filterRow2.add(new JLabel("Sort:"));filterRow2.add(sortBox);filterRow2.add(search);filterRow2.add(reset);
        controls.add(filterRow1);controls.add(filterRow2);
        panel.add(controls,BorderLayout.NORTH);

        bugsModel=new DefaultTableModel(new String[]{"ID","Title","Project","Severity","Priority","Status","Reporter","Developer","Reported"},0){
            @Override public boolean isCellEditable(int r,int c){return false;}
        };
        bugsTable=new JTable(bugsModel); bugsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(bugsTable),BorderLayout.CENTER);

        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton add=new JButton("Report Bug"), edit=new JButton("Update Selected"), assign=new JButton("Assign Developer"),
                comment=new JButton("Add Comment"), delete=new JButton("Delete Selected"), previous=new JButton("Previous"), next=new JButton("Next");
        add.addActionListener(e->reportBugDialog());
        edit.addActionListener(e->updateBugDialog());
        assign.addActionListener(e->assignBugDialog());
        comment.addActionListener(e->commentDialog());
        delete.addActionListener(e->deleteBug());
        previous.addActionListener(e->{if(currentPage>0){currentPage--;loadBugs();}});
        next.addActionListener(e->{currentPage++;loadBugs();});
        if(!"TESTER".equals(currentUser.role())) add.setEnabled("ADMIN".equals(currentUser.role()));
        if("TESTER".equals(currentUser.role())) {edit.setEnabled(false);assign.setEnabled(false);delete.setEnabled(false);}
        if("DEVELOPER".equals(currentUser.role())) {add.setEnabled(false);assign.setEnabled(false);delete.setEnabled(false);}
        actions.add(add);actions.add(edit);actions.add(assign);actions.add(comment);actions.add(delete);
        actions.add(previous);actions.add(next);
        panel.add(actions,BorderLayout.SOUTH);
        loadBugs();
        return panel;
    }

    private void loadBugs() {
        if(bugsModel==null)return;
        bugsModel.setRowCount(0);
        String keyword=searchField==null?"":searchField.getText().trim();
        String status=statusFilter==null?"ALL":String.valueOf(statusFilter.getSelectedItem());
        String priority=priorityFilter==null?"ALL":String.valueOf(priorityFilter.getSelectedItem());
        String sort=sortBox==null?"Newest first":String.valueOf(sortBox.getSelectedItem());
        String order=switch(sort){case "Oldest first"->"b.reported_at ASC";case "Priority (high first)"->"FIELD(b.priority,'URGENT','HIGH','MEDIUM','LOW'), b.reported_at DESC";case "Status"->"b.status, b.reported_at DESC";default->"b.reported_at DESC";};
        StringBuilder sql=new StringBuilder("""
            SELECT b.id,b.title,p.name project,b.severity,b.priority,b.status,
                   reporter.full_name reporter,COALESCE(dev.full_name,'Unassigned') developer,b.reported_at
            FROM bugs b JOIN projects p ON p.id=b.project_id
            JOIN users reporter ON reporter.id=b.reporter_id
            LEFT JOIN users dev ON dev.id=b.developer_id WHERE 1=1
            """);
        List<Object> args=new ArrayList<>();
        if(!keyword.isBlank()){sql.append(" AND (b.title LIKE ? OR b.description LIKE ? OR p.name LIKE ?)");String k="%"+keyword+"%";args.add(k);args.add(k);args.add(k);}
        if(!"ALL".equals(status)){sql.append(" AND b.status=?");args.add(status);}
        if(!"ALL".equals(priority)){sql.append(" AND b.priority=?");args.add(priority);}
        Choice selectedProject=projectFilter==null?null:(Choice)projectFilter.getSelectedItem();
        Choice selectedDeveloper=developerFilter==null?null:(Choice)developerFilter.getSelectedItem();
        if(selectedProject!=null&&selectedProject.id()>0){sql.append(" AND b.project_id=?");args.add(selectedProject.id());}
        if(selectedDeveloper!=null&&selectedDeveloper.id()>0){sql.append(" AND b.developer_id=?");args.add(selectedDeveloper.id());}
        if(fromDateField!=null&&!fromDateField.getText().isBlank()){sql.append(" AND DATE(b.reported_at)>=?");args.add(fromDateField.getText().trim());}
        if(toDateField!=null&&!toDateField.getText().isBlank()){sql.append(" AND DATE(b.reported_at)<=?");args.add(toDateField.getText().trim());}
        if("DEVELOPER".equals(currentUser.role())){sql.append(" AND b.developer_id=?");args.add(currentUser.id());}
        if("TESTER".equals(currentUser.role())){sql.append(" AND b.reporter_id=?");args.add(currentUser.id());}
        sql.append(" ORDER BY ").append(order).append(" LIMIT ? OFFSET ?");
        args.add(PAGE_SIZE);args.add(currentPage*PAGE_SIZE);
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql.toString())){
            for(int i=0;i<args.size();i++)p.setObject(i+1,args.get(i));
            try(ResultSet r=p.executeQuery()){while(r.next())bugsModel.addRow(new Object[]{
                    r.getInt("id"),r.getString("title"),r.getString("project"),r.getString("severity"),
                    r.getString("priority"),r.getString("status"),r.getString("reporter"),r.getString("developer"),r.getTimestamp("reported_at")});}
        }catch(SQLException ex){showError(ex);}
    }

    private JPanel projectsPanel(){
        JPanel panel=new JPanel(new BorderLayout(8,8));
        DefaultTableModel model=new DefaultTableModel(new String[]{"ID","Project","Description","Status","Start","End","Bug Count"},0){public boolean isCellEditable(int r,int c){return false;}};
        JTable table=new JTable(model);panel.add(new JScrollPane(table),BorderLayout.CENTER);
        JButton refresh=new JButton("Refresh"),create=new JButton("Create Project"),edit=new JButton("Edit Project");
        refresh.addActionListener(e->loadProjects(model));
        create.addActionListener(e->projectDialog(null,model));
        edit.addActionListener(e->{int row=table.getSelectedRow();if(row>=0)projectDialog((int)model.getValueAt(row,0),model);else JOptionPane.showMessageDialog(this,"Select a project.");});
        JPanel bottom=new JPanel();bottom.add(refresh);
        if("ADMIN".equals(currentUser.role())){bottom.add(create);bottom.add(edit);}
        panel.add(bottom,BorderLayout.SOUTH);loadProjects(model);return panel;
    }

    private void loadProjects(DefaultTableModel model){
        model.setRowCount(0);
        String sql="""
            SELECT p.id,p.name,p.description,p.status,p.start_date,p.end_date,COUNT(b.id) bug_count
            FROM projects p LEFT JOIN bugs b ON b.project_id=p.id
            GROUP BY p.id,p.name,p.description,p.status,p.start_date,p.end_date ORDER BY p.name
            """;
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql);ResultSet r=p.executeQuery()){
            while(r.next())model.addRow(new Object[]{r.getInt(1),r.getString(2),r.getString(3),r.getString(4),r.getDate(5),r.getDate(6),r.getInt(7)});
        }catch(SQLException ex){showError(ex);}
    }

    private void projectDialog(Integer projectId,DefaultTableModel model){
        JTextField name=new JTextField(),description=new JTextField(),start=new JTextField(LocalDate.now().toString()),end=new JTextField();
        JComboBox<String> status=new JComboBox<>(new String[]{"PLANNED","ACTIVE","COMPLETED","ON_HOLD"});
        if(projectId!=null)try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT * FROM projects WHERE id=?")){
            p.setInt(1,projectId);try(ResultSet r=p.executeQuery()){if(r.next()){name.setText(r.getString("name"));description.setText(r.getString("description"));status.setSelectedItem(r.getString("status"));start.setText(Objects.toString(r.getDate("start_date"),""));end.setText(Objects.toString(r.getDate("end_date"),""));}}
        }catch(SQLException ex){showError(ex);return;}
        JPanel form=formPanel(new String[]{"Name","Description","Start date (YYYY-MM-DD)","End date (YYYY-MM-DD)","Status"},new JComponent[]{name,description,start,end,status});
        if(JOptionPane.showConfirmDialog(this,form,projectId==null?"Create Project":"Edit Project",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){
            if(name.getText().isBlank()){warn("Project name is required.");return;}
            String sql=projectId==null?"INSERT INTO projects(name,description,start_date,end_date,status,created_by) VALUES(?,?,?,?,?,?)":
                    "UPDATE projects SET name=?,description=?,start_date=?,end_date=?,status=? WHERE id=?";
            try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql)){
                p.setString(1,name.getText().trim());p.setString(2,description.getText().trim());
                p.setDate(3,parseDate(start.getText()));p.setDate(4,parseDate(end.getText()));p.setString(5,(String)status.getSelectedItem());
                p.setInt(6,projectId==null?currentUser.id():projectId);p.executeUpdate();loadProjects(model);
            }catch(SQLException ex){showError(ex);}
        }
    }

    private void reportBugDialog(){
        JTextField title=new JTextField(),description=new JTextField();
        JComboBox<Choice> projectBox=new JComboBox<>(choices("SELECT id,name FROM projects ORDER BY name"));
        JComboBox<String> severity=new JComboBox<>(new String[]{"LOW","MEDIUM","HIGH","CRITICAL"});
        JComboBox<String> priority=new JComboBox<>(new String[]{"LOW","MEDIUM","HIGH","URGENT"});
        if(projectBox.getItemCount()==0){warn("Create a project before reporting a bug.");return;}
        JPanel form=formPanel(new String[]{"Title","Description","Project","Severity","Priority"},
                new JComponent[]{title,description,projectBox,severity,priority});
        if(JOptionPane.showConfirmDialog(this,form,"Report New Bug",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){
            if(title.getText().isBlank()||description.getText().isBlank()){warn("Title and description are required.");return;}
            try(Connection c=connect();PreparedStatement p=c.prepareStatement(
                    "INSERT INTO bugs(title,description,severity,priority,project_id,reporter_id) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                p.setString(1,title.getText().trim());p.setString(2,description.getText().trim());p.setString(3,(String)severity.getSelectedItem());
                p.setString(4,(String)priority.getSelectedItem());p.setInt(5,((Choice)projectBox.getSelectedItem()).id);p.setInt(6,currentUser.id());p.executeUpdate();
                try(ResultSet keys=p.getGeneratedKeys()){if(keys.next())logActivity(c,keys.getInt(1),null,"BUG_REPORTED","Bug reported: "+title.getText());}
                loadBugs();
            }catch(SQLException ex){showError(ex);}
        }
    }

    private void updateBugDialog(){
        Integer id=selectedBugId();if(id==null)return;
        String[] fields={"OPEN","IN_PROGRESS","RESOLVED","CLOSED","REOPENED"};
        JComboBox<String> status=new JComboBox<>(fields);
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT status FROM bugs WHERE id=?")){
            p.setInt(1,id);try(ResultSet r=p.executeQuery()){if(r.next())status.setSelectedItem(r.getString(1));}
        }catch(SQLException ex){showError(ex);return;}
        if(JOptionPane.showConfirmDialog(this,status,"Set bug status",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){
            String newStatus=(String)status.getSelectedItem();
            try(Connection c=connect()){
                c.setAutoCommit(false);
                try(PreparedStatement p=c.prepareStatement("UPDATE bugs SET status=?,resolved_at=CASE WHEN ?='RESOLVED' THEN CURRENT_TIMESTAMP ELSE NULL END WHERE id=?")){
                    p.setString(1,newStatus);p.setString(2,newStatus);p.setInt(3,id);p.executeUpdate();
                }
                logActivity(c,id,null,"STATUS_CHANGED","Status changed to "+newStatus);
                c.commit();loadBugs();
            }catch(SQLException ex){showError(ex);}
        }
    }

    private void assignBugDialog(){
        if(!"ADMIN".equals(currentUser.role()))return;
        Integer id=selectedBugId();if(id==null)return;
        JComboBox<Choice> devs=new JComboBox<>(choices("SELECT id,full_name FROM users WHERE role='DEVELOPER' AND active=TRUE ORDER BY full_name"));
        if(devs.getItemCount()==0){warn("No active developers found.");return;}
        if(JOptionPane.showConfirmDialog(this,devs,"Assign developer",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){
            Choice dev=(Choice)devs.getSelectedItem();
            try(Connection c=connect()){
                c.setAutoCommit(false);
                try(PreparedStatement p=c.prepareStatement("UPDATE bugs SET developer_id=? WHERE id=?")){
                    p.setInt(1,dev.id);p.setInt(2,id);p.executeUpdate();
                }
                logActivity(c,id,null,"DEVELOPER_ASSIGNED","Assigned to "+dev.name);
                c.commit();loadBugs();
            }catch(SQLException ex){showError(ex);}
        }
    }

    private void commentDialog(){
        Integer id=selectedBugId();if(id==null)return;
        JTextArea comment=new JTextArea(5,32);
        if(JOptionPane.showConfirmDialog(this,new JScrollPane(comment),"Add Bug Comment",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION&&!comment.getText().isBlank()){
            try(Connection c=connect()){
                c.setAutoCommit(false);
                try(PreparedStatement p=c.prepareStatement("INSERT INTO bug_comments(bug_id,user_id,comment_text) VALUES(?,?,?)")){
                    p.setInt(1,id);p.setInt(2,currentUser.id());p.setString(3,comment.getText().trim());p.executeUpdate();
                }
                logActivity(c,id,null,"COMMENT_ADDED","Comment added");
                c.commit();
            }catch(SQLException ex){showError(ex);}
        }
    }

    private void deleteBug(){
        if(!"ADMIN".equals(currentUser.role()))return;
        Integer id=selectedBugId();if(id==null)return;
        if(JOptionPane.showConfirmDialog(this,"Delete bug #"+id+"?","Confirm",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("DELETE FROM bugs WHERE id=?")){
            p.setInt(1,id);p.executeUpdate();loadBugs();
        }catch(SQLException ex){showError(ex);}
    }

    private JPanel reportsPanel(){
        JPanel panel=new JPanel(new BorderLayout(8,8));
        JComboBox<String> reportType=new JComboBox<>(new String[]{"Project-wise bug statistics","Developer workload","Status-wise summary","Advanced unresolved-bug analysis"});
        JButton run=new JButton("Generate Report");
        JTextArea output=new JTextArea();output.setEditable(false);output.setFont(new Font(Font.MONOSPACED,Font.PLAIN,13));
        JPanel top=new JPanel();top.add(reportType);top.add(run);panel.add(top,BorderLayout.NORTH);panel.add(new JScrollPane(output),BorderLayout.CENTER);
        run.addActionListener(e->{
            String choice=(String)reportType.getSelectedItem();
            String sql=switch(choice){
                case "Developer workload"->"""
                    SELECT u.full_name,COUNT(b.id) assigned,
                    SUM(CASE WHEN b.status IN ('OPEN','REOPENED','IN_PROGRESS') THEN 1 ELSE 0 END) unresolved,
                    AVG(CASE WHEN b.resolved_at IS NOT NULL THEN TIMESTAMPDIFF(HOUR,b.reported_at,b.resolved_at) END) avg_resolution_hours
                    FROM users u LEFT JOIN bugs b ON b.developer_id=u.id
                    WHERE u.role='DEVELOPER' GROUP BY u.id,u.full_name HAVING COUNT(b.id)>=0 ORDER BY assigned DESC
                    """;
                case "Status-wise summary"->"""
                    SELECT status,COUNT(*) total,MIN(reported_at) first_report,MAX(reported_at) latest_report
                    FROM bugs GROUP BY status HAVING COUNT(*)>=0 ORDER BY total DESC
                    """;
                case "Advanced unresolved-bug analysis"->"""
                    SELECT u.full_name,COUNT(b.id) unresolved_count
                    FROM users u JOIN bugs b ON b.developer_id=u.id
                    WHERE b.status IN ('OPEN','REOPENED','IN_PROGRESS')
                    GROUP BY u.id,u.full_name
                    HAVING COUNT(b.id)=(SELECT MAX(x.cnt) FROM
                      (SELECT COUNT(*) cnt FROM bugs WHERE status IN ('OPEN','REOPENED','IN_PROGRESS')
                       AND developer_id IS NOT NULL GROUP BY developer_id) x)
                    """;
                default->"""
                    SELECT p.name project_name,COUNT(b.id) total_bugs,
                    SUM(CASE WHEN b.status='RESOLVED' THEN 1 ELSE 0 END) resolved,
                    SUM(CASE WHEN b.status IN ('OPEN','REOPENED','IN_PROGRESS') THEN 1 ELSE 0 END) unresolved,
                    AVG(CASE WHEN b.resolved_at IS NOT NULL THEN TIMESTAMPDIFF(HOUR,b.reported_at,b.resolved_at) END) avg_resolution_hours,
                    MIN(b.reported_at) first_report,MAX(b.reported_at) latest_report
                    FROM projects p LEFT JOIN bugs b ON b.project_id=p.id
                    GROUP BY p.id,p.name HAVING COUNT(b.id)>=0 ORDER BY total_bugs DESC
                    """;
            };
            output.setText(runReport(sql));
        });
        return panel;
    }

    private String runReport(String sql){
        StringBuilder out=new StringBuilder();
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql);ResultSet r=p.executeQuery()){
            ResultSetMetaData md=r.getMetaData();int n=md.getColumnCount();
            for(int i=1;i<=n;i++)out.append(String.format("%-25s",md.getColumnLabel(i)));out.append("\n").append("-".repeat(Math.max(30,n*25))).append("\n");
            while(r.next()){for(int i=1;i<=n;i++)out.append(String.format("%-25s",Objects.toString(r.getObject(i),"NULL")));out.append("\n");}
            if(out.length()==0)out.append("No records found.");
        }catch(SQLException ex){out.append("Report error: ").append(ex.getMessage());}
        return out.toString();
    }

    private JPanel activityPanel(){
        JPanel panel=new JPanel(new BorderLayout());
        DefaultTableModel model=new DefaultTableModel(new String[]{"Time","Actor","Action","Bug ID","Details"},0){public boolean isCellEditable(int r,int c){return false;}};
        JTable table=new JTable(model);panel.add(new JScrollPane(table),BorderLayout.CENTER);
        JButton refresh=new JButton("Refresh");refresh.addActionListener(e->loadActivity(model));
        JPanel bottom=new JPanel();bottom.add(refresh);panel.add(bottom,BorderLayout.SOUTH);loadActivity(model);return panel;
    }

    private void loadActivity(DefaultTableModel model){
        model.setRowCount(0);
        String sql="SELECT a.created_at,u.full_name,a.action,a.bug_id,a.details FROM activity_logs a JOIN users u ON u.id=a.actor_id ORDER BY a.created_at DESC LIMIT 300";
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql);ResultSet r=p.executeQuery()){
            while(r.next())model.addRow(new Object[]{r.getTimestamp(1),r.getString(2),r.getString(3),r.getObject(4),r.getString(5)});
        }catch(SQLException ex){showError(ex);}
    }

    private JPanel usersPanel(){
        JPanel panel=new JPanel(new BorderLayout());
        DefaultTableModel model=new DefaultTableModel(new String[]{"ID","Username","Full Name","Role","Active"},0){public boolean isCellEditable(int r,int c){return false;}};
        JTable table=new JTable(model);panel.add(new JScrollPane(table),BorderLayout.CENTER);
        JButton add=new JButton("Add User"),refresh=new JButton("Refresh"),deactivate=new JButton("Deactivate Selected");
        add.addActionListener(e->addUser(model));refresh.addActionListener(e->loadUsers(model));
        deactivate.addActionListener(e->{int row=table.getSelectedRow();if(row<0)return;int id=(int)model.getValueAt(row,0);if(id==currentUser.id()){warn("You cannot deactivate your own account.");return;}
            try(Connection c=connect();PreparedStatement p=c.prepareStatement("UPDATE users SET active=FALSE WHERE id=?")){p.setInt(1,id);p.executeUpdate();loadUsers(model);}catch(SQLException ex){showError(ex);}});
        JPanel bottom=new JPanel();bottom.add(add);bottom.add(deactivate);bottom.add(refresh);panel.add(bottom,BorderLayout.SOUTH);loadUsers(model);return panel;
    }

    private void addUser(DefaultTableModel model){
        JTextField username=new JTextField(),password=new JTextField(),fullName=new JTextField();
        JComboBox<String> role=new JComboBox<>(new String[]{"ADMIN","DEVELOPER","TESTER"});
        JPanel form=formPanel(new String[]{"Username","Password","Full name","Role"},new JComponent[]{username,password,fullName,role});
        if(JOptionPane.showConfirmDialog(this,form,"Create User",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION){
            try(Connection c=connect();PreparedStatement p=c.prepareStatement("INSERT INTO users(username,password,full_name,role) VALUES(?,?,?,?)")){
                p.setString(1,username.getText().trim());p.setString(2,password.getText());p.setString(3,fullName.getText().trim());p.setString(4,(String)role.getSelectedItem());p.executeUpdate();loadUsers(model);
            }catch(SQLException ex){showError(ex);}
        }
    }

    private void loadUsers(DefaultTableModel model){
        model.setRowCount(0);
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT id,username,full_name,role,active FROM users ORDER BY id");ResultSet r=p.executeQuery()){
            while(r.next())model.addRow(new Object[]{r.getInt(1),r.getString(2),r.getString(3),r.getString(4),r.getBoolean(5)});
        }catch(SQLException ex){showError(ex);}
    }

    private record Choice(int id,String name){@Override public String toString(){return name;}}
    private Choice[] choices(String sql){
        List<Choice> list=new ArrayList<>();
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql);ResultSet r=p.executeQuery()){
            while(r.next())list.add(new Choice(r.getInt(1),r.getString(2)));
        }catch(SQLException ex){showError(ex);}
        return list.toArray(new Choice[0]);
    }

    private Integer selectedBugId(){
        int row=bugsTable.getSelectedRow();if(row<0){warn("Select a bug first.");return null;}return (Integer)bugsModel.getValueAt(row,0);
    }

    private static JPanel formPanel(String[] labels,JComponent[] components){
        JPanel p=new JPanel(new GridLayout(labels.length,2,8,8));
        for(int i=0;i<labels.length;i++){p.add(new JLabel(labels[i]));p.add(components[i]);}
        return p;
    }

    private static java.sql.Date parseDate(String value){
        if(value==null||value.isBlank())return null;
        return java.sql.Date.valueOf(value.trim());
    }

    private static int scalar(String sql){
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql);ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;}
        catch(SQLException ex){return 0;}
    }

    private void logActivity(Connection c,Integer bugId,Integer projectId,String action,String details)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("INSERT INTO activity_logs(bug_id,project_id,actor_id,action,details) VALUES(?,?,?,?,?)")){
            if(bugId==null)p.setNull(1,Types.INTEGER);else p.setInt(1,bugId);
            if(projectId==null)p.setNull(2,Types.INTEGER);else p.setInt(2,projectId);
            p.setInt(3,currentUser.id());p.setString(4,action);p.setString(5,details);p.executeUpdate();
        }
    }

    private void showError(Exception ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Database / Application Error",JOptionPane.ERROR_MESSAGE);}
    private void warn(String message){JOptionPane.showMessageDialog(this,message,"Notice",JOptionPane.WARNING_MESSAGE);}

    private static User loginDialog(){
        JTextField username=new JTextField("admin");JPasswordField password=new JPasswordField("admin123");
        JPanel form=formPanel(new String[]{"Username","Password"},new JComponent[]{username,password});
        while(true){
            int result=JOptionPane.showConfirmDialog(null,form,"Bug Tracker Login",JOptionPane.OK_CANCEL_OPTION);
            if(result!=JOptionPane.OK_OPTION)return null;
            try{
                User user=authenticate(username.getText().trim(),new String(password.getPassword()));
                if(user!=null)return user;
                JOptionPane.showMessageDialog(null,"Invalid username or password.");
            }catch(SQLException ex){JOptionPane.showMessageDialog(null,"Database connection failed:\n"+ex.getMessage()+"\n\nCheck MySQL settings in README.");return null;}
        }
    }

    public static void main(String[] args){
        SwingUtilities.invokeLater(()->{
            try{Class.forName("com.mysql.cj.jdbc.Driver");initializeDatabase();}
            catch(Exception ex){JOptionPane.showMessageDialog(null,"Startup failed: "+ex.getMessage()+"\nCheck MySQL installation and credentials.","Startup Error",JOptionPane.ERROR_MESSAGE);return;}
            User user=loginDialog();if(user!=null)new BugTrackingApp(user).setVisible(true);
        });
    }
}
