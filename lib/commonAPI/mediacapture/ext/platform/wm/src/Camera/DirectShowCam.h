#ifndef _DIRECTSHOW_H
#define _DIRECTSHOW_H
#include "Camera.h"
#include "DShowCam.h"
class CDirectShowCam : public CCamera
{
public:
	CDirectShowCam(LPCTSTR szDeviceName);
	~CDirectShowCam();
	static BOOL enumerate(rho::Vector<rho::String>& arIDs, rho::Hashtable<rho::String, eCamType>& camLookUp);
	virtual void takeFullScreen();
	virtual BOOL showPreview();
	virtual BOOL hidePreview();
	virtual void Capture();
protected:
	void SetFlashMode();
	void SetResolution();
	void setCameraProperties();
	virtual void RedrawViewerWnd(RECT& pos);
	void ResetResolution();
private:
	CDShowCam* m_pDSCam;

};
#endif