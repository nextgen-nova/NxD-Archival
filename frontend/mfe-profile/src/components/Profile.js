import React, { useState, useEffect } from "react";
import { useAuth } from "../AuthContext";
import { fetchProfile, updateProfile, changePassword } from "../api/profileApi";
import "./Profile.css";

export default function Profile() {
  const { user, refreshUser } = useAuth();
  const [profileData,    setProfileData]    = useState(null);
  const [loadingProfile, setLoadingProfile] = useState(true);
  const [isEditing,      setIsEditing]      = useState(false);
  const [activeSection,  setActiveSection]  = useState("profile");
  const [submitting,     setSubmitting]     = useState(false);
  const [toast,          setToast]          = useState(null);
  const [errors,         setErrors]         = useState({});
  const [formData,       setFormData]       = useState({ name:"", email:"", currentPassword:"", newPassword:"", confirmPassword:"" });

  useEffect(() => {
    (async () => {
      try {
        setLoadingProfile(true);
        const res  = await fetchProfile();
        const data = res.data?.data || res.data;
        setProfileData(data);
        setFormData(p => ({ ...p, name:data.name, email:data.email }));
      } catch {
        setProfileData(user);
        setFormData(p => ({ ...p, name:user?.name||"", email:user?.email||"" }));
      } finally { setLoadingProfile(false); }
    })();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const showToast = (message, type="success") => { setToast({ message, type }); setTimeout(()=>setToast(null),3500); };

  const handleUpdateProfile = async e => {
    e.preventDefault(); setErrors({});
    try {
      setSubmitting(true);
      const res     = await updateProfile({ name:formData.name, email:formData.email });
      const updated = res.data?.data || res.data;
      setProfileData(updated);
      await refreshUser();
      setIsEditing(false);
      showToast("Profile updated successfully");
    } catch (err) {
      const msg = err.response?.data?.message || "Failed to update profile";
      const fe  = err.response?.data?.data;
      if (fe) setErrors(fe); else showToast(msg,"error");
    } finally { setSubmitting(false); }
  };

  const handleChangePassword = async e => {
    e.preventDefault(); setErrors({});
    if (formData.newPassword !== formData.confirmPassword) { setErrors({ confirmPassword:"Passwords do not match" }); return; }
    try {
      setSubmitting(true);
      await changePassword({ currentPassword:formData.currentPassword, newPassword:formData.newPassword, confirmPassword:formData.confirmPassword });
      setFormData(p=>({...p,currentPassword:"",newPassword:"",confirmPassword:""}));
      showToast("Password changed successfully");
    } catch (err) {
      const msg = err.response?.data?.message || "Failed to change password";
      const fe  = err.response?.data?.data;
      if (fe) setErrors(fe); else showToast(msg,"error");
    } finally { setSubmitting(false); }
  };

  const d = profileData || user || {};
  const memberSince = d.createdAt ? new Date(d.createdAt).toLocaleDateString("en-GB",{day:"numeric",month:"long",year:"numeric"}) : "—";
  const lastLogin   = d.lastLogin  ? new Date(d.lastLogin).toLocaleString("en-GB",{day:"numeric",month:"short",year:"numeric",hour:"2-digit",minute:"2-digit"}) : "—";
  const initials    = name => name?.split(" ").map(n=>n[0]).join("").slice(0,2).toUpperCase()||"?";

  const navItems = [
    { key:"profile",  label:"Profile Information" },
    { key:"security", label:"Security" },
    { key:"activity", label:"Activity Log" },
  ];

  return (
    <div className="profile-container">
      {toast && <div className={`profile-toast profile-toast-${toast.type}`}>{toast.message}</div>}

      <div className="profile-sidebar">
        <div className="profile-avatar-section">
          <div className="profile-avatar-large">{loadingProfile?"…":initials(d.name)}</div>
          <h2>{d.name}</h2>
          <p className="profile-role">{d.role}</p>
          <p className="profile-id">{d.employeeId}</p>
          {d.active !== undefined && (
            <span className={`profile-status-badge ${d.active?"active":"inactive"}`}>
              {d.active?"● Active":"○ Inactive"}
            </span>
          )}
        </div>
        <nav className="profile-nav">
          {navItems.map(item=>(
            <button key={item.key} className={`profile-nav-item${activeSection===item.key?" active":""}`} onClick={()=>setActiveSection(item.key)}>
              {item.label}
            </button>
          ))}
        </nav>
      </div>

      <div className="profile-main">
        {activeSection==="profile" && (
          <div className="profile-section">
            <div className="section-header">
              <div><h2>Profile Information</h2><p>Your personal information stored in the system</p></div>
              {!isEditing && <button className="btn-edit" onClick={()=>setIsEditing(true)}>Edit Profile</button>}
            </div>
            {isEditing ? (
              <form onSubmit={handleUpdateProfile} className="profile-form">
                <div className="form-row">
                  <div className="form-group">
                    <label>Full Name</label>
                    <input type="text" value={formData.name} onChange={e=>setFormData({...formData,name:e.target.value})} required minLength={2}/>
                    {errors.name && <span className="field-error">{errors.name}</span>}
                  </div>
                  <div className="form-group">
                    <label>Email Address</label>
                    <input type="email" value={formData.email} onChange={e=>setFormData({...formData,email:e.target.value})} required/>
                    {errors.email && <span className="field-error">{errors.email}</span>}
                  </div>
                </div>
                <div className="form-row">
                  <div className="form-group"><label>Employee ID</label><input type="text" value={d.employeeId} disabled className="disabled-input"/></div>
                  <div className="form-group"><label>Role</label><input type="text" value={d.role} disabled className="disabled-input"/></div>
                </div>
                <div className="form-actions">
                  <button type="button" className="btn-cancel" onClick={()=>{setIsEditing(false);setErrors({});}}>Cancel</button>
                  <button type="submit" className="btn-save" disabled={submitting}>{submitting?"Saving…":"Save Changes"}</button>
                </div>
              </form>
            ) : (
              <div className="profile-details">
                {loadingProfile ? <div style={{padding:24,color:"var(--gray-3)"}}>Loading profile…</div> : (<>
                  <div className="detail-row">
                    <div className="detail-item"><label>Full Name</label><p>{d.name}</p></div>
                    <div className="detail-item"><label>Email Address</label><p>{d.email||"Not set"}</p></div>
                  </div>
                  <div className="detail-row">
                    <div className="detail-item"><label>Employee ID</label><p>{d.employeeId}</p></div>
                    <div className="detail-item"><label>Role</label><p><span className={`role-badge role-${d.role?.toLowerCase()}`}>{d.role}</span></p></div>
                  </div>
                  <div className="detail-row">
                    <div className="detail-item"><label>Account Status</label><p><span className={`status-badge ${d.active?"active":"inactive"}`}>{d.active?"Active":"Inactive"}</span></p></div>
                    <div className="detail-item"><label>Member Since</label><p>{memberSince}</p></div>
                  </div>
                  <div className="detail-row">
                    <div className="detail-item"><label>Last Login</label><p>{lastLogin}</p></div>
                  </div>
                </>)}
              </div>
            )}
          </div>
        )}

        {activeSection==="security" && (
          <div className="profile-section">
            <div className="section-header"><div><h2>Security Settings</h2><p>Change your password to keep your account secure</p></div></div>
            <form onSubmit={handleChangePassword} className="profile-form">
              <h3>Change Password</h3>
              <div className="form-group">
                <label>Current Password</label>
                <input type="password" value={formData.currentPassword} onChange={e=>setFormData({...formData,currentPassword:e.target.value})} required placeholder="Enter current password"/>
                {errors.currentPassword && <span className="field-error">{errors.currentPassword}</span>}
              </div>
              <div className="form-group">
                <label>New Password</label>
                <input type="password" value={formData.newPassword} onChange={e=>setFormData({...formData,newPassword:e.target.value})} required minLength={6} placeholder="Min. 6 characters"/>
                {errors.newPassword && <span className="field-error">{errors.newPassword}</span>}
              </div>
              <div className="form-group">
                <label>Confirm New Password</label>
                <input type="password" value={formData.confirmPassword} onChange={e=>setFormData({...formData,confirmPassword:e.target.value})} required minLength={6} placeholder="Confirm new password"/>
                {errors.confirmPassword && <span className="field-error">{errors.confirmPassword}</span>}
              </div>
              <div className="password-requirements">
                <p className="requirements-title">Password Requirements:</p>
                <ul>
                  <li className={formData.newPassword.length>=6?"valid":""}>At least 6 characters</li>
                  <li className={formData.newPassword&&formData.newPassword===formData.confirmPassword?"valid":""}>Passwords match</li>
                </ul>
              </div>
              <div className="form-actions">
                <button type="submit" className="btn-save" disabled={submitting}>{submitting?"Updating…":"Update Password"}</button>
              </div>
            </form>
            <div className="security-info">
              <h3>Session Information</h3>
              <div className="info-grid">
                <div className="info-item"><div><p className="info-label">Last Login</p><p className="info-value">{lastLogin}</p></div></div>
                <div className="info-item"><div><p className="info-label">Account Role</p><p className="info-value">{d.role}</p></div></div>
              </div>
            </div>
          </div>
        )}

        {activeSection==="activity" && (
          <div className="profile-section">
            <div className="section-header"><div><h2>Activity Log</h2><p>Recent account activity</p></div></div>
            <div className="activity-list">
              <div className="activity-item">
                <div className="activity-icon login"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/><polyline points="10 17 15 12 10 7"/><line x1="15" y1="12" x2="3" y2="12"/></svg></div>
                <div className="activity-content"><p className="activity-title">Last login</p><p className="activity-details">{lastLogin}</p></div>
              </div>
              <div className="activity-item">
                <div className="activity-icon update"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg></div>
                <div className="activity-content"><p className="activity-title">Account created</p><p className="activity-details">{memberSince}</p></div>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
