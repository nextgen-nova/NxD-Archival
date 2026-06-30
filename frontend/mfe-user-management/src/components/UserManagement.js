import React, { useState, useEffect, useCallback, useRef } from "react";
import { createPortal } from "react-dom";
import { useAuth } from "../AuthContext";
import { fetchUsers, fetchUserStats, createUser, updateUser, toggleUserStatus, deleteUser } from "../api/userApi";
import "./UserManagement.css";

function Toast({ message, type, onClose }) {
  useEffect(() => { const t = setTimeout(onClose, 3500); return () => clearTimeout(t); }, [onClose]);
  return <div className={`um-toast um-toast-${type}`}><span>{message}</span><button onClick={onClose}>×</button></div>;
}

function FormModal({ title, onSubmit, onClose, children, submitLabel, submitting }) {
  const contentRef = useRef(null);

  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      const firstField = contentRef.current?.querySelector(
        'input:not([type="hidden"]):not([disabled]), select:not([disabled]), textarea:not([disabled])'
      );
      firstField?.focus();
    });
    return () => cancelAnimationFrame(frame);
  }, []);

  return createPortal(
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={e => e.stopPropagation()} ref={contentRef} role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal-header">
          <h2>{title}</h2>
          <button className="modal-close" onClick={onClose} disabled={submitting}>
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
        <form onSubmit={onSubmit} autoComplete="off" data-lpignore="true" data-1p-ignore="true">
          <div className="autofill-decoy" aria-hidden="true">
            <input type="text" name="fake_username" autoComplete="username" tabIndex={-1} />
            <input type="password" name="fake_password" autoComplete="current-password" tabIndex={-1} />
          </div>
          <div style={{ padding:"20px 22px 4px" }}>{children}</div>
          <div className="modal-actions">
            <button type="button" className="btn-cancel" onClick={onClose} disabled={submitting}>Cancel</button>
            <button type="submit" className="btn-submit" disabled={submitting}>{submitting ? "Saving…" : submitLabel}</button>
          </div>
        </form>
      </div>
    </div>,
    document.body
  );
}

function Pagination({ currentPage, totalPages, totalCount, pageSize, onPageChange, onPageSizeChange }) {
  const from = totalCount === 0 ? 0 : currentPage * pageSize + 1;
  const to   = Math.min((currentPage + 1) * pageSize, totalCount);
  const getPages = () => {
    if (totalPages <= 7) return Array.from({ length: totalPages }, (_, i) => i);
    if (currentPage <= 3)             return [0,1,2,3,4,"…",totalPages-1];
    if (currentPage >= totalPages-4)  return [0,"…",totalPages-5,totalPages-4,totalPages-3,totalPages-2,totalPages-1];
    return [0,"…",currentPage-1,currentPage,currentPage+1,"…",totalPages-1];
  };
  return (
    <div className="um-pagination">
      <div className="pagination-left">
        <span className="pagination-info">{totalCount===0?"No records":`Showing ${from}–${to} of ${totalCount} users`}</span>
        <div className="pagination-size">
          <span>Show</span>
          <select value={pageSize} onChange={e=>onPageSizeChange(Number(e.target.value))}>
            {[5,10,20,50].map(n=><option key={n} value={n}>{n}</option>)}
          </select>
          <span>per page</span>
        </div>
      </div>
      <div className="pagination-right">
        <button className="page-nav-btn" onClick={()=>onPageChange(0)} disabled={currentPage===0} title="First">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><polyline points="11 17 6 12 11 7"/><polyline points="18 17 13 12 18 7"/></svg>
        </button>
        <button className="page-nav-btn" onClick={()=>onPageChange(currentPage-1)} disabled={currentPage===0} title="Previous">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><polyline points="15 18 9 12 15 6"/></svg>
        </button>
        {totalPages>0 && getPages().map((p,i)=>
          p==="…"
            ? <span key={`e${i}`} className="page-ellipsis">…</span>
            : <button key={p} className={`page-num-btn${p===currentPage?" active":""}`} onClick={()=>onPageChange(p)}>{p+1}</button>
        )}
        <button className="page-nav-btn" onClick={()=>onPageChange(currentPage+1)} disabled={currentPage>=totalPages-1||totalPages===0} title="Next">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><polyline points="9 18 15 12 9 6"/></svg>
        </button>
        <button className="page-nav-btn" onClick={()=>onPageChange(totalPages-1)} disabled={currentPage>=totalPages-1||totalPages===0} title="Last">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><polyline points="13 17 18 12 13 7"/><polyline points="6 17 11 12 6 7"/></svg>
        </button>
      </div>
    </div>
  );
}

const EMPTY_FORM = { employeeId:"", name:"", email:"", role:"EMPLOYEE", password:"", active:true };

export default function UserManagement() {
  const { user } = useAuth();
  const [users,       setUsers]       = useState([]);
  const [stats,       setStats]       = useState(null);
  const [totalPages,  setTotalPages]  = useState(0);
  const [totalCount,  setTotalCount]  = useState(0);
  const [loading,     setLoading]     = useState(true);
  const [submitting,  setSubmitting]  = useState(false);
  const [showAdd,     setShowAdd]     = useState(false);
  const [showEdit,    setShowEdit]    = useState(false);
  const [selectedUser,setSelectedUser]= useState(null);
  const [toast,       setToast]       = useState(null);
  const [searchTerm,  setSearchTerm]  = useState("");
  const [filterRole,  setFilterRole]  = useState("ALL");
  const [currentPage, setCurrentPage] = useState(0);
  const [pageSize,    setPageSize]    = useState(10);
  const [formData,    setFormData]    = useState(EMPTY_FORM);

  const showToast = (message, type="success") => setToast({ message, type });
  const resetForm = () => setFormData(EMPTY_FORM);

  const loadUsers = useCallback(async () => {
    try {
      setLoading(true);
      const params = { page:currentPage, size:pageSize, sort:"createdAt", dir:"desc" };
      if (searchTerm.trim()) params.search = searchTerm.trim();
      if (filterRole !== "ALL") params.role = filterRole;
      const res  = await fetchUsers(params);
      const data = res.data?.data || res.data;
      setUsers(data.content || []);
      setTotalPages(data.totalPages || 0);
      setTotalCount(data.totalElements || 0);
    } catch (err) {
      showToast(err.response?.data?.message || "Failed to load users", "error");
    } finally { setLoading(false); }
  }, [currentPage, pageSize, searchTerm, filterRole]);

  const loadStats = useCallback(async () => {
    try { const res = await fetchUserStats(); setStats(res.data?.data || res.data); } catch {}
  }, []);

  useEffect(() => { loadUsers(); loadStats(); }, [loadUsers, loadStats]);
  useEffect(() => {
    const t = setTimeout(() => { setCurrentPage(0); loadUsers(); }, 400);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchTerm, filterRole]);

  const handleAdd = async e => {
    e.preventDefault();
    try { setSubmitting(true); await createUser(formData); showToast("User created successfully"); setShowAdd(false); resetForm(); loadUsers(); loadStats(); }
    catch (err) { showToast(err.response?.data?.message || "Failed to create user", "error"); }
    finally { setSubmitting(false); }
  };

  const handleEdit = async e => {
    e.preventDefault();
    try { setSubmitting(true); await updateUser(selectedUser.employeeId, formData); showToast("User updated successfully"); setShowEdit(false); setSelectedUser(null); resetForm(); loadUsers(); }
    catch (err) { showToast(err.response?.data?.message || "Failed to update user", "error"); }
    finally { setSubmitting(false); }
  };

  const handleDelete = async id => {
    if (!window.confirm(`Delete user ${id}? This cannot be undone.`)) return;
    try { await deleteUser(id); showToast("User deleted"); loadUsers(); loadStats(); }
    catch (err) { showToast(err.response?.data?.message || "Failed to delete user", "error"); }
  };

  const handleToggle = async (id, curr) => {
    try { await toggleUserStatus(id, !curr); showToast(`User ${!curr?"activated":"deactivated"}`); loadUsers(); }
    catch (err) { showToast(err.response?.data?.message || "Failed to update status", "error"); }
  };

  const openEdit = u => { setSelectedUser(u); setFormData({ employeeId:u.employeeId, name:u.name, email:u.email, role:u.role, password:"", active:u.active }); setShowEdit(true); };
  const fmtDate  = d => d ? new Date(d).toLocaleDateString("en-GB",{day:"2-digit",month:"short",year:"numeric",hour:"2-digit",minute:"2-digit"}) : "—";
  const initials = n => n?.split(" ").map(x=>x[0]).join("").slice(0,2).toUpperCase()||"?";

  if (user?.role !== "ADMIN") return (
    <div className="user-management-container">
      <div className="access-denied">
        <svg width="52" height="52" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
        <h2>Access Denied</h2><p>Only administrators can manage users.</p>
      </div>
    </div>
  );

  return (
    <div className="user-management-container">
      {toast && <Toast message={toast.message} type={toast.type} onClose={()=>setToast(null)}/>}

      <div className="um-header">
        <div className="um-title-section">
          <h1>User Management</h1>
          <p className="um-subtitle">Manage system users and access permissions{totalCount>0&&<span className="um-count"> — {totalCount} users</span>}</p>
        </div>
        <button
          type="button"
          className="um-add-btn"
          onClick={()=>setShowAdd(true)}
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          Add New User
        </button>
      </div>

      {stats && (
        <div className="um-stats-bar">
          <div className="stat-pill"><span className="stat-label">Total</span><span className="stat-value">{stats.total}</span></div>
          <div className="stat-pill active"><span className="stat-label">Active</span><span className="stat-value">{stats.active}</span></div>
          <div className="stat-pill inactive"><span className="stat-label">Inactive</span><span className="stat-value">{stats.inactive}</span></div>
          <div className="stat-pill admin"><span className="stat-label">Admins</span><span className="stat-value">{stats.admins}</span></div>
          <div className="stat-pill employee"><span className="stat-label">Employees</span><span className="stat-value">{stats.employees}</span></div>
        </div>
      )}

      <div className="um-filters">
        <div className="um-search-box">
          <svg className="search-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="11" cy="11" r="8"/><line x1="16.5" y1="16.5" x2="22" y2="22"/></svg>
          <input type="text" placeholder="Search by name, email, or employee ID…" value={searchTerm} onChange={e=>setSearchTerm(e.target.value)}/>
          {searchTerm && <button className="search-clear" onClick={()=>setSearchTerm("")}>×</button>}
        </div>
        <div className="um-role-filter">
          <label>Role</label>
          <select value={filterRole} onChange={e=>{setFilterRole(e.target.value);setCurrentPage(0);}}>
            <option value="ALL">All Roles</option>
            <option value="ADMIN">Admin</option>
            <option value="EMPLOYEE">Employee</option>
          </select>
        </div>
      </div>

      <div className="um-table-container">
        {loading ? (
          <div className="um-loading"><div className="um-spinner"/>Loading users…</div>
        ) : users.length === 0 ? (
          <div className="um-no-data"><svg width="38" height="38" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg><p>No users match your search criteria</p></div>
        ) : (
          <table className="um-table">
            <thead><tr>
              <th>Employee ID</th><th>Name</th><th>Email</th><th>Role</th>
              <th>Status</th><th>Created</th><th>Last Login</th><th>Actions</th>
            </tr></thead>
            <tbody>
              {users.map(u=>(
                <tr key={u.id||u.employeeId}>
                  <td><span className="employee-id">{u.employeeId}</span></td>
                  <td><div className="user-name-cell"><div className="user-avatar">{initials(u.name)}</div><span style={{fontWeight:500,fontSize:13}}>{u.name}</span></div></td>
                  <td style={{color:"var(--gray-2)",fontSize:12.5}}>{u.email}</td>
                  <td><span className={`role-badge role-${u.role?.toLowerCase()}`}>{u.role}</span></td>
                  <td>
                    <button className={`status-toggle ${u.active?"active":"inactive"}`} onClick={()=>handleToggle(u.employeeId,u.active)} disabled={u.employeeId==="ADMIN001"&&u.active}>
                      {u.active?"● Active":"○ Inactive"}
                    </button>
                  </td>
                  <td style={{fontFamily:"var(--mono)",fontSize:11,color:"var(--gray-3)"}}>{fmtDate(u.createdAt)}</td>
                  <td style={{fontFamily:"var(--mono)",fontSize:11,color:"var(--gray-3)"}}>{fmtDate(u.lastLogin)}</td>
                  <td>
                    <div className="action-buttons">
                      <button className="action-btn edit-btn" onClick={()=>openEdit(u)} title="Edit">
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
                      </button>
                      <button className="action-btn delete-btn" onClick={()=>handleDelete(u.employeeId)} title="Delete" disabled={u.employeeId==="ADMIN001"}>
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <Pagination currentPage={currentPage} totalPages={totalPages} totalCount={totalCount} pageSize={pageSize} onPageChange={setCurrentPage} onPageSizeChange={s=>{setPageSize(s);setCurrentPage(0);}}/>

      {showAdd && (
        <FormModal title="Add New User" onSubmit={handleAdd} onClose={()=>{setShowAdd(false);resetForm();}} submitLabel="Add User" submitting={submitting}>
          <div className="form-group"><label>Employee ID *</label><input type="text" name="user_employee_id" autoComplete="off" data-lpignore="true" data-1p-ignore="true" value={formData.employeeId} onChange={e=>setFormData({...formData,employeeId:e.target.value.toUpperCase()})} required placeholder="e.g. EMP004"/></div>
          <div className="form-group"><label>Full Name *</label><input type="text" name="user_full_name" autoComplete="off" data-lpignore="true" data-1p-ignore="true" value={formData.name} onChange={e=>setFormData({...formData,name:e.target.value})} required placeholder="Enter full name"/></div>
          <div className="form-group"><label>Email *</label><input type="text" inputMode="email" name="user_email_address" autoComplete="off" data-lpignore="true" data-1p-ignore="true" value={formData.email} onChange={e=>setFormData({...formData,email:e.target.value})} required placeholder="user@example.com"/></div>
          <div className="form-group"><label>Password *</label><input type="password" name="user_new_password" autoComplete="new-password" data-lpignore="true" data-1p-ignore="true" value={formData.password} onChange={e=>setFormData({...formData,password:e.target.value})} required placeholder="Min. 6 characters" minLength={6}/></div>
          <div className="form-group"><label>Role *</label>
            <select value={formData.role} onChange={e=>setFormData({...formData,role:e.target.value})}>
              <option value="EMPLOYEE">Employee</option>
              <option value="ADMIN">Administrator</option>
            </select>
          </div>
          <div className="form-group checkbox-group"><label><input type="checkbox" checked={formData.active} onChange={e=>setFormData({...formData,active:e.target.checked})}/><span>Active User</span></label></div>
        </FormModal>
      )}

      {showEdit && selectedUser && (
        <FormModal title="Edit User" onSubmit={handleEdit} onClose={()=>{setShowEdit(false);setSelectedUser(null);resetForm();}} submitLabel="Save Changes" submitting={submitting}>
          <div className="form-group"><label>Employee ID</label><input type="text" name="edit_user_employee_id" autoComplete="off" data-lpignore="true" data-1p-ignore="true" value={formData.employeeId} disabled className="disabled-input"/></div>
          <div className="form-group"><label>Full Name *</label><input type="text" name="edit_user_full_name" autoComplete="off" data-lpignore="true" data-1p-ignore="true" value={formData.name} onChange={e=>setFormData({...formData,name:e.target.value})} required/></div>
          <div className="form-group"><label>Email *</label><input type="text" inputMode="email" name="edit_user_email_address" autoComplete="off" data-lpignore="true" data-1p-ignore="true" value={formData.email} onChange={e=>setFormData({...formData,email:e.target.value})} required/></div>
          <div className="form-group"><label>New Password <span style={{color:"var(--gray-3)",fontWeight:400,textTransform:"none"}}>(leave blank to keep)</span></label><input type="password" name="edit_user_new_password" autoComplete="new-password" data-lpignore="true" data-1p-ignore="true" value={formData.password} onChange={e=>setFormData({...formData,password:e.target.value})} placeholder="New password" minLength={6}/></div>
          <div className="form-group"><label>Role *</label>
            <select value={formData.role} onChange={e=>setFormData({...formData,role:e.target.value})} disabled={selectedUser.employeeId==="ADMIN001"}>
              <option value="EMPLOYEE">Employee</option>
              <option value="ADMIN">Administrator</option>
            </select>
          </div>
          <div className="form-group checkbox-group"><label><input type="checkbox" checked={formData.active} onChange={e=>setFormData({...formData,active:e.target.checked})} disabled={selectedUser.employeeId==="ADMIN001"}/><span>Active User</span></label></div>
        </FormModal>
      )}
    </div>
  );
}
